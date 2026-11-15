#include "FleetPanel.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "I18N.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "Widgets/Button.hpp"
#include "format.hpp"
#include "libslic3r/DeviceWorkspaceState.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "slic3r/Utils/Http.hpp"
#include "slic3r/Utils/U1JobJournal.hpp"

#include <optional>
#include <future>
#include <nlohmann/json.hpp>

#include <wx/bitmap.h>
#include <wx/log.h>
#include <wx/mstream.h>
#include <wx/panel.h>
#include <wx/sizer.h>
#include <wx/statbmp.h>
#include <wx/stattext.h>

#include <algorithm>
#include <cmath>
#include <set>
#include <utility>

namespace Slic3r::GUI {

namespace {

constexpr int REFRESH_MS = 5000;
// The picture on a card, in device-independent pixels.
constexpr int PICTURE_W = 160, PICTURE_H = 90;
// A still picture larger than this is not taken.
constexpr size_t PICTURE_MAX_BYTES = 4 * 1024 * 1024;

/// The host part of a preset's print_host when it is a LAN address, as resolve_printer_lan_host() gives it.
std::string lan_host_of(const std::string &address)
{
    std::string host = address;
    if (const auto scheme = host.find("://"); scheme != std::string::npos)
        host = host.substr(scheme + 3);
    if (const auto slash = host.find('/'); slash != std::string::npos)
        host = host.substr(0, slash);
    return DeviceWorkspaceState::lan_host_from_address(host).empty() ? std::string() : host;
}

wxString band_name(Fleet::Band band)
{
    switch (band) {
    case Fleet::Band::NeedsAttention: return _L("Needs attention");
    case Fleet::Band::Printing: return _L("Printing");
    case Fleet::Band::Ready: return _L("Ready");
    case Fleet::Band::Offline: return _L("Offline");
    case Fleet::Band::NotMonitored: break;
    }
    return _L("Not monitored");
}

wxColour band_colour(Fleet::Band band, bool dark)
{
    switch (band) {
    case Fleet::Band::NeedsAttention: return NIATheme::danger(dark);
    case Fleet::Band::Printing: return NIATheme::accent(dark);
    case Fleet::Band::Ready: return NIATheme::ready(dark);
    case Fleet::Band::Offline:
    case Fleet::Band::NotMonitored: break;
    }
    return NIATheme::text_muted(dark);
}

wxString percent(double fraction) { return wxString::Format("%d%%", int(std::lround(fraction * 100.))); }

wxString detail(const Fleet::Reading &reading, const Fleet::Verdict &verdict, const std::string &host)
{
    switch (verdict.reason) {
    case Fleet::Reason::NoAddress: return _L("No address. Enter it in this printer's Connection settings.");
    // TRN %1% is a network address.
    case Fleet::Reason::NoAnswer: return format_wxstr(_L("No answer at %1%."), host);
    case Fleet::Reason::NotMoonraker:
        return _L("Something answers at this address, but not as a Klipper (Moonraker) printer. Its state is not read here.");
    case Fleet::Reason::FirmwareNotReady:
        return reading.firmware_message.empty() ? _L("The printer's firmware is not ready.") : from_u8(reading.firmware_message);
    case Fleet::Reason::PrintError: return _L("The last print ended with an error.");
    // TRN %1% is a file name, %2% how far the print is, such as "25%".
    case Fleet::Reason::Paused: return format_wxstr(_L("Paused: %1% at %2%"), from_u8(reading.filename), percent(reading.progress));
    case Fleet::Reason::StartOpen: return _L("A Start sent from this app has no settled outcome.");
    case Fleet::Reason::None: break;
    }
    if (verdict.band == Fleet::Band::Printing)
        // TRN %1% is a file name, %2% how far the print is, such as "25%".
        return format_wxstr(_L("%1% at %2%"), from_u8(reading.filename), percent(reading.progress));
    if (reading.print_state == "complete")
        return _L("The last print is complete.");
    if (reading.print_state == "cancelled")
        return _L("The last print was cancelled.");
    return _L("Idle.");
}

/// One read request to one printer. Runs on the worker thread.
Fleet::Reading read_printer(const std::string &host, const std::shared_ptr<std::atomic<bool>> &stop)
{
    Fleet::Reading reading;
    reading.has_address = true;
    Http::get("http://" + host + "/printer/objects/query?webhooks&print_stats&virtual_sdcard")
        .timeout_connect(3)
        .timeout_max(5)
        .on_progress([stop](Http::Progress, bool &cancel) { cancel = stop->load(); })
        .on_complete([&reading](std::string body, unsigned) {
            reading.reached = true;
            const auto doc  = nlohmann::json::parse(body, nullptr, false);
            if (!doc.is_discarded() && doc.is_object() && doc.contains("result") && doc["result"].is_object() && doc["result"].contains("status"))
                Fleet::read_status(doc["result"]["status"], reading);
        })
        // An HTTP status means something is there, though it did not answer this question.
        .on_error([&reading](std::string, std::string, unsigned status) { reading.reached = status != 0; })
        .perform_sync();
    return reading;
}

/// The printer's first enabled camera, from Moonraker's list of cameras: where it serves a still picture and
/// where its stream. Both empty when it lists none. One read request; runs on the worker thread.
std::pair<std::string, std::string> camera_addresses(const std::string &host, const std::shared_ptr<std::atomic<bool>> &stop)
{
    std::pair<std::string, std::string> found;
    Http::get("http://" + host + "/server/webcams/list")
        .timeout_connect(3)
        .timeout_max(5)
        .on_progress([stop](Http::Progress, bool &cancel) { cancel = stop->load(); })
        .on_complete([&found, &host](std::string body, unsigned) {
            const auto doc = nlohmann::json::parse(body, nullptr, false);
            if (doc.is_discarded() || !doc.is_object() || !doc.contains("result") || !doc["result"].is_object())
                return;
            const auto cams = doc["result"].find("webcams");
            if (cams == doc["result"].end() || !cams->is_array())
                return;
            // Moonraker gives a path on the printer's web port, or a whole address.
            const auto address = [&host](const nlohmann::json &cam, const char *key) {
                const auto it = cam.find(key);
                if (it == cam.end() || !it->is_string() || it->get<std::string>().empty())
                    return std::string();
                std::string url = it->get<std::string>();
                if (url.rfind("http://", 0) != 0 && url.rfind("https://", 0) != 0)
                    url = "http://" + host.substr(0, host.find(':')) + (url.front() == '/' ? "" : "/") + url;
                return url;
            };
            for (const auto &cam : *cams) {
                if (!cam.is_object() || !cam.value("enabled", true))
                    continue;
                found = {address(cam, "snapshot_url"), address(cam, "stream_url")};
                if (!found.first.empty() || !found.second.empty())
                    return;
            }
        })
        .on_error([](std::string, std::string, unsigned) {})
        .perform_sync();
    return found;
}

/// One still picture from a camera, as the bytes it sent. Empty when there is none. Runs on the worker thread.
std::string camera_picture(const std::string &url, const std::shared_ptr<std::atomic<bool>> &stop)
{
    std::string bytes;
    Http::get(url)
        .timeout_connect(3)
        .timeout_max(5)
        .size_limit(PICTURE_MAX_BYTES)
        .on_progress([stop](Http::Progress, bool &cancel) { cancel = stop->load(); })
        .on_complete([&bytes](std::string body, unsigned) { bytes = std::move(body); })
        .on_error([](std::string, std::string, unsigned) {})
        .perform_sync();
    return bytes;
}

} // namespace

FleetPanel::FleetPanel(wxWindow *parent, OpenFn open)
    : wxScrolledWindow(parent, wxID_ANY)
    , m_open(std::move(open))
    , m_alive(std::make_shared<std::atomic<bool>>(true))
    , m_reading(std::make_shared<std::atomic<bool>>(false))
    , m_stop(std::make_shared<std::atomic<bool>>(false))
    , m_cameras(std::make_shared<std::map<std::string, Camera>>())
{
    SetScrollRate(0, FromDIP(12));
    auto *outer = new wxBoxSizer(wxVERTICAL);
    m_filters   = new wxBoxSizer(wxHORIZONTAL);
    outer->Add(m_filters, 0, wxLEFT | wxTOP | wxRIGHT, FromDIP(12));
    m_empty = new wxStaticText(this, wxID_ANY, wxEmptyString);
    outer->Add(m_empty, 0, wxALL, FromDIP(16));
    m_rows = new wxBoxSizer(wxVERTICAL);
    outer->Add(m_rows, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, FromDIP(12));
    SetSizer(outer);
    m_timer.SetOwner(this);
    Bind(wxEVT_TIMER, [this](wxTimerEvent &) { read_printers(); });
    on_sys_color_changed();
}

FleetPanel::~FleetPanel()
{
    m_alive->store(false);
    // The pass is told to stop and waited for, so no thread of this panel outlives it.
    m_stop->store(true);
    if (m_reader.joinable())
        m_reader.join();
    stop_streams();
}

void FleetPanel::stop_streams()
{
    // A stream gives its read up within about a second; that is not waited for on this thread.
    for (auto &stream : m_streams)
        m_retired.retire(std::move(stream.second));
    m_streams.clear();
    m_frame_pending.clear();
}

void FleetPanel::show_picture(const std::string &host, const wxImage &image)
{
    if (!image.IsOk() || image.GetWidth() <= 0 || image.GetHeight() <= 0) {
        m_snapshots.erase(host); // no picture now: an old one is not left standing
        return;
    }
    const wxSize box   = FromDIP(wxSize(PICTURE_W, PICTURE_H));
    const double scale = std::min(double(box.x) / image.GetWidth(), double(box.y) / image.GetHeight());
    wxImage &kept = m_snapshots[host];
    kept          = image.Scale(std::max(1, int(image.GetWidth() * scale)), std::max(1, int(image.GetHeight() * scale)), wxIMAGE_QUALITY_BILINEAR);
    if (const auto it = m_pictures.find(host); it != m_pictures.end())
        it->second->SetBitmap(wxBitmap(kept));
}

std::vector<FleetPanel::Printer> FleetPanel::printers()
{
    std::vector<Printer> list;
    const PresetBundle  *bundle = wxGetApp().preset_bundle;
    if (bundle == nullptr)
        return list;
    const std::string selected = bundle->printers.get_edited_preset().name;
    std::set<std::string> seen;
    // The selected preset first, so that it is the card for its address when several presets share one.
    const auto add = [&](const Preset &preset, const DynamicPrintConfig &config) {
        const std::string host = config.has("print_host") ? lan_host_of(config.opt_string("print_host")) : std::string();
        const bool        is_selected = preset.name == selected;
        if (host.empty() ? !is_selected : !seen.insert(host).second)
            return;
        list.push_back({preset.name, host, is_selected});
    };
    add(bundle->printers.get_edited_preset(), bundle->printers.get_edited_preset().config);
    for (const Preset &preset : bundle->printers)
        if (preset.is_visible && !preset.is_default && preset.name != selected)
            add(preset, preset.config);
    return list;
}

void FleetPanel::read_printers()
{
    std::vector<std::string> hosts;
    for (const Printer &printer : printers())
        if (!printer.host.empty())
            hosts.push_back(printer.host);
    if (hosts.empty() || m_reading->exchange(true))
        return;
    if (m_reader.joinable())
        m_reader.join(); // the previous pass has finished: its flag was clear
    // A printer whose stream is playing needs no still picture. One whose stream ended is asked again where
    // its camera is: the address may have changed.
    std::set<std::string> playing, ended;
    for (const auto &[host, stream] : m_streams)
        if (stream->ended())
            ended.insert(host);
        else if (stream->frames() > 0)
            playing.insert(host);
    m_reader = std::thread([hosts, playing, ended, alive = m_alive, reading = m_reading, stop = m_stop, cameras = m_cameras, panel = this] {
        std::map<std::string, Fleet::Reading> readings;
        std::map<std::string, std::string>    pictures; // by address: the bytes of a still picture, empty for none
        std::map<std::string, std::string>    streams;  // by address: where the camera's stream is, empty for none
        for (const std::string &host : ended)
            cameras->erase(host);
        // The printers are read side by side, eight at a time, so that one which does not answer holds up
        // nobody else's card. The camera list is touched only on this thread, before and after.
        struct Read
        {
            Fleet::Reading        reading;
            std::string           picture;
            std::optional<Camera> camera; // what is known of its camera after this read; none when it is no Moonraker printer
        };
        const auto read_one = [&stop, &playing](const std::string &host, std::optional<Camera> known) {
            Read read;
            read.reading = read_printer(host, stop);
            // From this app's own record, not from the printer.
            read.reading.start_open = U1Jobs::unresolved(host).has_value();
            // A still picture from the printer's camera, when it answers as a Moonraker printer and lists one.
            // The list is asked for once; a printer that was away is asked again when it is back.
            if (!read.reading.moonraker || stop->load())
                return read;
            if (!known) {
                const auto found = camera_addresses(host, stop);
                known            = Camera{found.first, found.second};
            }
            read.camera = known;
            if (!known->snapshot.empty() && playing.count(host) == 0 && !stop->load())
                read.picture = camera_picture(known->snapshot, stop);
            return read;
        };
        for (size_t first = 0; first < hosts.size() && !stop->load(); first += 8) {
            std::vector<std::pair<std::string, std::future<Read>>> batch;
            for (size_t i = first; i < std::min(hosts.size(), first + 8); ++i) {
                const auto known = cameras->find(hosts[i]);
                batch.emplace_back(hosts[i], std::async(std::launch::async, read_one, hosts[i],
                                                        known == cameras->end() ? std::optional<Camera>() : std::optional<Camera>(known->second)));
            }
            for (auto &[host, pending] : batch) {
                Read read = pending.get();
                if (read.camera) {
                    (*cameras)[host] = *read.camera;
                    streams.emplace(host, read.camera->stream);
                } else if (read.reading.moonraker == false)
                    cameras->erase(host);
                pictures.emplace(host, std::move(read.picture));
                readings.emplace(host, std::move(read.reading));
            }
        }
        // The panel joins this thread before it goes, so the application is still there for this call.
        if (!stop->load())
            wxGetApp().CallAfter([alive, panel, readings = std::move(readings), pictures = std::move(pictures), streams = std::move(streams)] {
                if (!alive->load() || wxGetApp().is_closing() || !panel->m_timer.IsRunning())
                    return;
                for (const auto &[host, one] : readings)
                    panel->m_readings[host] = one;
                // The camera streams: one per printer that lists one, started here and dropped when the printer
                // is away, lists another address, or the stream ended (it is then started again).
                for (auto it = panel->m_streams.begin(); it != panel->m_streams.end();) {
                    const auto wanted = streams.find(it->first);
                    if (wanted == streams.end() || wanted->second.empty() || wanted->second != it->second->url() || it->second->ended()) {
                        panel->m_frame_pending.erase(it->first);
                        panel->m_retired.retire(std::move(it->second));
                        it = panel->m_streams.erase(it);
                    } else
                        ++it;
                }
                for (const auto &[host, url] : streams) {
                    if (url.empty() || panel->m_streams.count(host) != 0)
                        continue;
                    auto pending = std::make_shared<std::atomic<bool>>(false);
                    panel->m_frame_pending[host] = pending;
                    panel->m_streams[host] = std::make_unique<MjpegStream>(url, [alive, panel, host, pending](std::string jpeg) {
                        // On the stream's thread. One picture at a time travels to the main thread.
                        if (pending->exchange(true))
                            return;
                        wxGetApp().CallAfter([alive, panel, host, pending, jpeg = std::move(jpeg)] {
                            pending->store(false);
                            if (!alive->load() || wxGetApp().is_closing() || panel->m_streams.count(host) == 0)
                                return;
                            wxImage image;
                            {
                                wxLogNull            quiet;
                                wxMemoryInputStream bytes(jpeg.data(), jpeg.size());
                                image.LoadFile(bytes, wxBITMAP_TYPE_JPEG);
                            }
                            if (!image.IsOk())
                                return; // a damaged picture is skipped; the last good one stays
                            const bool had = panel->m_snapshots.count(host) != 0;
                            panel->show_picture(host, image);
                            if (!had)
                                panel->rebuild(false); // the card gets its picture window
                        });
                    });
                }
                // Still pictures, for the printers whose stream is not playing.
                for (const auto &[host, bytes] : pictures) {
                    const auto stream = panel->m_streams.find(host);
                    if (stream != panel->m_streams.end() && !stream->second->ended() && stream->second->frames() > 0)
                        continue;
                    wxImage image;
                    if (!bytes.empty()) {
                        wxLogNull            quiet; // a camera may send something that is no picture
                        wxMemoryInputStream stream_of(bytes.data(), bytes.size());
                        image.LoadFile(stream_of, wxBITMAP_TYPE_ANY);
                    }
                    panel->show_picture(host, image);
                }
                panel->rebuild(false);
            });
        reading->store(false);
    });
}

void FleetPanel::set_active(bool active)
{
    if (active) {
        rebuild(false);
        read_printers();
        m_timer.Start(REFRESH_MS);
    } else {
        m_timer.Stop();
        stop_streams(); // no camera is read while the list does not show
    }
}

void FleetPanel::reload()
{
    rebuild(true);
    if (m_timer.IsRunning())
        read_printers();
}

void FleetPanel::on_sys_color_changed()
{
    SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    rebuild(true);
}

void FleetPanel::rebuild(bool force)
{
    const bool dark = wxGetApp().dark_mode();
    struct Row
    {
        Printer        printer;
        bool           read = false;
        Fleet::Reading reading;
        Fleet::Verdict verdict;
    };
    std::vector<Row> rows;
    for (const Printer &printer : printers()) {
        Row row;
        row.printer = printer;
        if (printer.host.empty()) {
            row.read    = true;
            row.verdict = Fleet::classify(row.reading); // no address
        } else if (const auto it = m_readings.find(printer.host); it != m_readings.end()) {
            row.read    = true;
            row.reading = it->second;
            row.verdict = Fleet::classify(row.reading);
        }
        rows.push_back(std::move(row));
    }
    // A printer not read yet is listed after those that were, in the Offline group until its answer is known.
    std::stable_sort(rows.begin(), rows.end(), [](const Row &a, const Row &b) {
        const int ka = a.read ? int(a.verdict.band) : int(Fleet::Band::NotMonitored) + 1;
        const int kb = b.read ? int(b.verdict.band) : int(Fleet::Band::NotMonitored) + 1;
        return ka != kb ? ka < kb : a.printer.name < b.printer.name;
    });

    std::string shown = std::string(dark ? "dark" : "light") + "|" + std::to_string(m_filter);
    for (const Row &row : rows)
        shown += "|" + row.printer.name + ":" + row.printer.host + ":" + (row.printer.selected ? "s" : "") + ":" + (row.read ? "r" : "") + ":" +
                 std::to_string(int(row.verdict.band)) + ":" + std::to_string(int(row.verdict.reason)) + ":" + row.reading.filename + ":" +
                 std::to_string(int(std::lround(row.reading.progress * 100.))) + ":" + row.reading.print_state + ":" + row.reading.firmware_message +
                 (m_snapshots.count(row.printer.host) != 0 ? ":pic" : "");
    if (!force && shown == m_shown)
        return;
    m_shown = shown;
    // The same cards in the same order: only their detail lines changed (a file name, how far a print is).
    // They are written in place, so a card and the picture playing on it are not built anew every few seconds.
    std::string layout = std::string(dark ? "dark" : "light") + "|" + std::to_string(m_filter);
    for (const Row &row : rows)
        layout += "|" + row.printer.name + ":" + row.printer.host + ":" + (row.printer.selected ? "s" : "") + ":" + (row.read ? "r" : "") + ":" +
                  std::to_string(int(row.verdict.band)) + ":" + std::to_string(int(row.verdict.reason)) + (m_snapshots.count(row.printer.host) != 0 ? ":pic" : "");
    if (!force && layout == m_layout) {
        for (const Row &row : rows)
            if (const auto it = m_texts.find(row.printer.host); it != m_texts.end() && it->second.detail != nullptr && row.read) {
                const wxString text = detail(row.reading, row.verdict, row.printer.host);
                if (it->second.detail->GetLabel() != text)
                    it->second.detail->SetLabel(text);
            }
        Layout();
        return;
    }
    m_layout = layout;

    // The filters carry the number of printers in each group. Offline also holds what is not monitored or not read yet.
    const auto group = [](const Row &row) {
        return !row.read || row.verdict.band == Fleet::Band::NotMonitored ? int(Fleet::Band::Offline) : int(row.verdict.band);
    };
    int counts[4] = {0, 0, 0, 0};
    for (const Row &row : rows)
        ++counts[group(row)];
    Freeze();
    m_filters->Clear(true);
    m_filter_buttons.clear();
    const auto add_filter = [&](int filter, const wxString &text) {
        auto *button = new ::Button(this, text);
        button->SetStyle(filter == m_filter ? ButtonStyle::Confirm : ButtonStyle::Regular, ButtonType::Window);
        button->Bind(wxEVT_BUTTON, [this, filter](wxCommandEvent &) {
            // Not on the stack of the click: the rebuild destroys the button.
            wxGetApp().CallAfter([alive = m_alive, this, filter] {
                if (!alive->load())
                    return;
                m_filter = filter;
                rebuild(true);
            });
        });
        m_filters->Add(button, 0, wxRIGHT, FromDIP(8));
        m_filter_buttons.push_back(button);
    };
    // TRN %1% is a number of printers.
    add_filter(-1, format_wxstr(_L("All (%1%)"), rows.size()));
    for (Fleet::Band band : {Fleet::Band::NeedsAttention, Fleet::Band::Printing, Fleet::Band::Ready, Fleet::Band::Offline})
        // TRN %1% is a group of printers such as "Printing", %2% the number of printers in it.
        add_filter(int(band), format_wxstr(_L("%1% (%2%)"), band_name(band), counts[int(band)]));

    m_rows->Clear(true);
    m_pictures.clear();
    m_texts.clear();
    int listed = 0;
    for (const Row &row : rows) {
        if (m_filter >= 0 && group(row) != m_filter)
            continue;
        ++listed;
        auto *card = new wxPanel(this);
        card->SetBackgroundColour(NIATheme::surface(dark));
        auto *line = new wxBoxSizer(wxHORIZONTAL);
        auto *text = new wxBoxSizer(wxVERTICAL);
        const auto add_text = [&](wxSizer *to, const wxString &label, const wxColour &colour, bool bold) {
            auto *item = new wxStaticText(card, wxID_ANY, label);
            if (bold)
                item->SetFont(item->GetFont().Bold());
            item->SetForegroundColour(colour);
            item->SetBackgroundColour(NIATheme::surface(dark));
            to->Add(item, 0, wxBOTTOM, FromDIP(2));
            return item;
        };
        auto *state = new wxBoxSizer(wxVERTICAL);
        add_text(state, row.read ? band_name(row.verdict.band) : _L("Reading…"),
                 row.read ? band_colour(row.verdict.band, dark) : NIATheme::text_muted(dark), true);
        state->SetMinSize(FromDIP(150), -1);
        line->Add(state, 0, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(12));
        // The camera's last still picture, for a printer that has one.
        if (const auto shot = m_snapshots.find(row.printer.host); shot != m_snapshots.end()) {
            auto *picture = new wxStaticBitmap(card, wxID_ANY, wxBitmap(shot->second));
            picture->SetToolTip(_L("The printer's camera, while this list shows."));
            line->Add(picture, 0, wxALIGN_CENTER_VERTICAL | wxTOP | wxBOTTOM | wxRIGHT, FromDIP(8));
            m_pictures[row.printer.host] = picture;
        }

        add_text(text, from_u8(row.printer.name), NIATheme::text(dark), true);
        wxString where = row.printer.host.empty() ? wxString() : from_u8(row.printer.host);
        if (row.printer.selected)
            where += (where.empty() ? wxString() : wxString::FromUTF8("  ·  ")) + _L("Selected for slicing");
        if (!where.empty())
            add_text(text, where, NIATheme::text_muted(dark), false);
        if (row.read)
            m_texts[row.printer.host].detail = add_text(text, detail(row.reading, row.verdict, row.printer.host), NIATheme::text(dark), false);
        line->Add(text, 1, wxALIGN_CENTER_VERTICAL | wxTOP | wxBOTTOM, FromDIP(10));

        auto *open = new ::Button(card, _L("Open"));
        open->SetStyle(ButtonStyle::Regular, ButtonType::Window);
        open->SetToolTip(_L("Shows this printer on the Monitor side. The printer selected for slicing stays as it is."));
        open->Enable(!row.printer.host.empty());
        open->Bind(wxEVT_BUTTON, [this, host = row.printer.host, name = row.printer.name](wxCommandEvent &) {
            // Not on the stack of the click: opening leaves this side.
            wxGetApp().CallAfter([alive = m_alive, this, host, name] {
                if (alive->load() && m_open)
                    m_open(host, name);
            });
        });
        line->Add(open, 0, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(12));
        card->SetSizer(line);
        m_rows->Add(card, 0, wxEXPAND | wxTOP, FromDIP(8));
    }
    m_empty->SetForegroundColour(NIATheme::text_muted(dark));
    m_empty->SetLabel(rows.empty() ? _L("No printer has an address yet. Enter one in a printer's Connection settings.") :
                      listed == 0  ? _L("No printer is in this group.") : wxString());
    m_empty->Show(listed == 0);
    wxGetApp().UpdateDarkUIWin(this);
    Thaw();
    FitInside();
    Layout();
}

} // namespace Slic3r::GUI
