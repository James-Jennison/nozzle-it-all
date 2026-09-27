// Nozzle It All Desktop's slicing engine: a native command-line front end over the shared slicing pipeline
// (app/src/main/cpp/bridge/slic3r_engine.cpp) that Android (JNI) and the Web App (engine/wasm/bridge/wasm_engine.cpp)
// also call, so all three platforms slice identically.
//
//   nozzle-engine <request.txt>      slice; prints "progress <0-100>" lines on stdout, exits 0 on success
//   nozzle-engine --version
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

#include <atomic>
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

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--version") {
        std::cout << "nozzle-engine 1 (libslic3r, shared Android pipeline, native)" << std::endl;
        return 0;
    }
    if (argc != 2) {
        std::cerr << "usage: nozzle-engine <request.txt> | --version" << std::endl;
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
