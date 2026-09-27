// Nozzle It All Desktop's slicing engine: a native command-line front end over the shared slicing pipeline
// (app/src/main/cpp/bridge/slic3r_engine.cpp) that Android (JNI) and the Web App (engine/wasm/bridge/wasm_engine.cpp)
// also call, so all three platforms slice identically.
//
//   nozzle-engine <request.txt>      slice; prints "progress <0-100>" lines on stdout, exits 0 on success
//   nozzle-engine --version
//   nozzle-engine --schema           every print/filament/printer setting libslic3r defines, as JSON on stdout
//
// The request file uses the Web App's line format (one field per line, tab-separated):
//   out\t<gcode path>
//   profile\t<path>              (repeatable, in load order: machine, process, filament...)
//   set\t<key>\t<value>          (repeatable config override)
//   object\t<model path>\t<x mm>\t<y mm>\t<rotation z deg>\t<scale>\t<tool index, 1-based, 0 = default>
// x/y are relative to the bed centre, as in the Web App.
//
// stdout carries only the progress lines: libslic3r's own log output (Boost.Log) is sent to stderr with everything else.
// Exit codes: 0 success, 1 slice failed (message on stderr), 2 bad usage/request, 3 cancelled (SIGINT/SIGTERM).
// Everything is local; nothing here performs network I/O.
#include "slic3r_engine.hpp"

#include <libslic3r/PrintConfig.hpp>
#include <libslic3r/Preset.hpp>

#include <atomic>
#include <cfloat>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <tuple>
#include <vector>

#include <unistd.h>

namespace {

struct BadRequest : std::runtime_error { using std::runtime_error::runtime_error; };

struct Request {
    std::string out;
    std::vector<std::string> profiles;
    std::vector<std::pair<std::string, std::string>> overrides;
    std::vector<std::tuple<std::string, engine::ModelTransform, int>> objects;
};

// Same parser as engine/wasm/bridge/wasm_engine.cpp, so a request written for one engine works for the other.
Request parse(const std::string& text) {
    Request r;
    std::istringstream in(text);
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::vector<std::string> f;
        std::stringstream ls(line);
        std::string part;
        while (std::getline(ls, part, '\t')) f.push_back(part);
        if (f.empty()) continue;
        if (f[0] == "out" && f.size() == 2) r.out = f[1];
        else if (f[0] == "profile" && f.size() == 2) r.profiles.push_back(f[1]);
        else if (f[0] == "set" && f.size() == 3) r.overrides.emplace_back(f[1], f[2]);
        else if (f[0] == "set" && f.size() == 2) r.overrides.emplace_back(f[1], "");
        else if (f[0] == "object" && f.size() == 7) {
            engine::ModelTransform t;
            try {
                t.offset_x_mm = std::stod(f[2]); t.offset_y_mm = std::stod(f[3]); t.rotation_z_deg = std::stod(f[4]); t.scale = std::stod(f[5]);
                r.objects.emplace_back(f[1], t, std::stoi(f[6]));
            } catch (const std::logic_error&) { throw BadRequest("Bad number in object line: " + line); }
        } else throw BadRequest("Unrecognised request line: " + f[0]);
    }
    if (r.out.empty() || r.objects.empty() || r.profiles.empty()) throw BadRequest("The slice request is incomplete.");
    // The shared pipeline skips a profile path it cannot read; here a missing input is a request error instead.
    auto readable = [](const std::string& p) { std::ifstream f(p, std::ios::binary); return f.good(); };
    for (const auto& p : r.profiles) if (!readable(p)) throw BadRequest("Cannot read profile " + p);
    for (const auto& o : r.objects) if (!readable(std::get<0>(o))) throw BadRequest("Cannot read model " + std::get<0>(o));
    return r;
}

std::atomic<bool> g_cancel{false};
void on_signal(int) { g_cancel = true; }

} // namespace

namespace {
std::string json_string(const std::string& s) {
    std::string o = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) { char b[8]; std::snprintf(b, sizeof b, "\\u%04x", c); o += b; } else o += char(c);
        }
    }
    return o + "\"";
}

const char* type_name(Slic3r::ConfigOptionType t) {
    using namespace Slic3r;
    switch (t) {
        case coFloat: return "float"; case coFloats: return "floats"; case coInt: return "int"; case coInts: return "ints";
        case coString: return "string"; case coStrings: return "strings"; case coPercent: return "percent"; case coPercents: return "percents";
        case coFloatOrPercent: return "float_or_percent"; case coFloatsOrPercents: return "floats_or_percents";
        case coPoint: return "point"; case coPoints: return "points"; case coPoint3: return "point3";
        case coBool: return "bool"; case coBools: return "bools"; case coEnum: return "enum"; case coEnums: return "enums";
        default: return "other";
    }
}

/**
 * The settings schema: for every option a preset can carry, its scope (process, filament, printer), type, labels, category,
 * help text, units, limits, detail level, choices and default, straight from libslic3r's PrintConfigDef. Nozzle generates
 * its settings screens from this instead of copying any slicer's UI.
 */
int dump_schema() {
    using namespace Slic3r;
    const std::pair<const char*, const std::vector<std::string>*> scopes[] = {
        {"process", &Preset::print_options()}, {"filament", &Preset::filament_options()}, {"printer", &Preset::printer_options()}};
    const char* modes[] = {"simple", "advanced", "expert", "develop"};
    std::cout << "{\"schema\":\"nozzle.settings\",\"version\":1,\"options\":[";
    bool first = true;
    for (const auto& [scope, keys] : scopes) {
        for (const std::string& key : *keys) {
            const ConfigOptionDef* d = print_config_def.get(key);
            if (d == nullptr) continue;
            std::cout << (first ? "\n" : ",\n") << "{\"key\":" << json_string(key) << ",\"scope\":\"" << scope << "\",\"type\":\"" << type_name(d->type) << "\"";
            first = false;
            if (!d->label.empty()) std::cout << ",\"label\":" << json_string(d->label);
            if (!d->full_label.empty() && d->full_label != d->label) std::cout << ",\"fullLabel\":" << json_string(d->full_label);
            if (!d->category.empty()) std::cout << ",\"category\":" << json_string(d->category);
            if (!d->tooltip.empty()) std::cout << ",\"tooltip\":" << json_string(d->tooltip);
            if (!d->sidetext.empty()) std::cout << ",\"units\":" << json_string(d->sidetext);
            if (d->min > -FLT_MAX) std::cout << ",\"min\":" << d->min;
            if (d->max < FLT_MAX) std::cout << ",\"max\":" << d->max;
            if (!d->ratio_over.empty()) std::cout << ",\"ratioOver\":" << json_string(d->ratio_over);
            int m = int(d->mode); std::cout << ",\"mode\":\"" << (m >= 0 && m < 4 ? modes[m] : "develop") << "\"";
            if (d->readonly) std::cout << ",\"readonly\":true";
            if (d->multiline) std::cout << ",\"multiline\":true";
            if (d->is_code) std::cout << ",\"code\":true";
            if (d->nullable) std::cout << ",\"nullable\":true";
            if (!d->enum_values.empty()) {
                std::cout << ",\"choices\":[";
                for (size_t i = 0; i < d->enum_values.size(); ++i)
                    std::cout << (i ? "," : "") << "{\"value\":" << json_string(d->enum_values[i]) << ",\"label\":"
                              << json_string(i < d->enum_labels.size() ? d->enum_labels[i] : d->enum_values[i]) << "}";
                std::cout << "]";
                if (d->gui_type == ConfigOptionDef::GUIType::f_enum_open || d->gui_type == ConfigOptionDef::GUIType::i_enum_open) std::cout << ",\"openChoices\":true";
            }
            if (d->default_value) std::cout << ",\"default\":" << json_string(d->default_value->serialize());
            std::cout << "}";
        }
    }
    std::cout << "\n]}" << std::endl;
    return 0;
}
} // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--schema") return dump_schema();
    if (argc == 2 && std::string(argv[1]) == "--version") {
        std::cout << "nozzle-engine 1 (libslic3r, shared Android pipeline, native)" << std::endl;
        return 0;
    }
    if (argc != 2) {
        std::cerr << "usage: nozzle-engine <request.txt> | --version | --schema" << std::endl;
        return 2;
    }
    Request req;
    try {
        std::ifstream f(argv[1], std::ios::binary);
        if (!f) throw BadRequest(std::string("Cannot read request file ") + argv[1]);
        std::stringstream ss; ss << f.rdbuf();
        req = parse(ss.str());
    } catch (const std::exception& e) {
        std::cerr << e.what() << std::endl;
        return 2;
    }

    // Keep stdout for progress lines only: the original stdout becomes a private stream, and fd 1 (where libslic3r's
    // log sink writes) is pointed at stderr.
    std::fflush(stdout);
    int progress_fd = dup(STDOUT_FILENO);
    FILE* progress = progress_fd >= 0 ? fdopen(progress_fd, "w") : nullptr;
    if (!progress || dup2(STDERR_FILENO, STDOUT_FILENO) < 0) { std::cerr << "Cannot set up the progress stream." << std::endl; return 1; }
    auto report = [progress](int p) { std::fprintf(progress, "progress %d\n", p); std::fflush(progress); };

    std::signal(SIGINT, on_signal);
    std::signal(SIGTERM, on_signal);
    engine::reset_cancel();

    // The slice runs on a worker thread; this thread reports progress and forwards a cancellation signal.
    report(0);
    std::atomic<bool> finished{false};
    int code = 0;
    std::string message;
    std::thread worker([&]() {
        try {
            engine::slice_multi_object(req.objects, req.out, req.profiles, req.overrides);
        } catch (const engine::SliceCancelled&) {
            code = 3; message = "Slicing cancelled";
        } catch (const std::exception& e) {
            code = 1; message = e.what();
        } catch (...) {
            code = 1; message = "The engine stopped unexpectedly.";
        }
        finished = true;
    });
    int last = 0;
    bool cancel_sent = false;
    while (!finished) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (g_cancel && !cancel_sent) { engine::request_cancel(); cancel_sent = true; }
        int p = engine::slice_progress();
        if (p != last) { report(p); last = p; }
    }
    worker.join();
    if (code != 0) {
        std::cerr << (message.empty() ? "Slicing failed" : message) << std::endl;
        return code;
    }
    if (last != 100) report(100);
    return 0;
}
