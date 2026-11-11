#pragma once

#include <string>
#include <vector>

namespace Slic3r::NIASettingsMigration {

enum class SourceKind { NiaDesktop, HybridOrca, SnapmakerOrca, OrcaSlicer };

struct SourceSpec {
    SourceKind               kind;
    std::string              label;
    std::string              path;
    std::vector<std::string> config_names;
};

struct Candidate {
    SourceSpec  source;
    std::string config_name;
};

struct Result {
    bool        success{false};
    bool        partial{false};
    bool        partial_confirmation_required{false};
    bool        cancelled{false};
    std::string report_path;
    std::string backup_path;
    std::string staging_path;
    std::vector<std::string> skipped_files;
    std::vector<std::string> sanitized_files;
    std::string error;
};

struct RecoveryResult {
    bool        recovered{false};
    bool        busy{false};
    std::string recovery_path;
    std::string error;
};

bool is_meaningful_data_dir(const std::string &path);
bool import_declined(const std::string &target);
std::string record_import_declined(const std::string &target);
bool import_requested(const std::string &target);
std::string record_import_requested(const std::string &target);
std::string clear_import_requested(const std::string &target);
std::vector<Candidate> discover(const std::vector<SourceSpec> &ordered_sources, const std::string &target,
                                bool allow_existing_target = false);
RecoveryResult recover_interrupted(const std::string &target);
Result copy_candidate(const Candidate &candidate, const std::string &target, const std::string &target_config_name,
                      bool consent_granted, bool allow_partial = true, const Result *prepared = nullptr);
std::string discard_prepared_candidate(const Result &prepared, const std::string &target);

} // namespace Slic3r::NIASettingsMigration
