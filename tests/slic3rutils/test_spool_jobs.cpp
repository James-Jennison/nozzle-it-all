#include <catch2/catch_all.hpp>

#include "slic3r/Utils/SpoolJobs.hpp"

#include <string>
#include <vector>

using namespace Slic3r;
using Catch::Matchers::WithinAbs;

namespace {

int spool_in_slot(Spools::Inventory &inventory, int slot, double grams, const std::string &printer = "192.168.1.243")
{
    Spools::Spool spool;
    spool.name      = "PLA";
    spool.material  = "PLA";
    spool.initial_g = grams;
    spool.location  = Spools::Location{Spools::Location::Kind::Printer, printer, slot};
    return inventory.add(spool, 1);
}

U1Jobs::Job job_of(U1Jobs::State state, std::vector<double> grams, std::vector<int> map, const std::string &printer = "192.168.1.243")
{
    U1Jobs::Job job;
    job.id           = U1Jobs::new_id();
    job.printer      = printer;
    job.state        = state;
    job.start_sent   = true;
    job.toolhead_map = std::move(map);
    for (double g : grams) {
        U1Jobs::Origin::Filament filament;
        filament.used  = g > 0.;
        filament.grams = g;
        job.origin.filaments.push_back(filament);
    }
    return job;
}

} // namespace

TEST_CASE("A finished job takes each filament's grams from the spool in the slot it printed from", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    const int first  = spool_in_slot(inventory, 0, 1000.);
    const int third  = spool_in_slot(inventory, 2, 1000.);
    // Filament 1 printed from slot 3, filament 2 from slot 1, filament 3 was not used.
    const U1Jobs::Job job = job_of(U1Jobs::State::Completed, {40., 10., 0.}, {2, 0, 1});
    CHECK(SpoolJobs::record_job(inventory, job, 5) == 2);
    CHECK_THAT(Spools::remaining(*inventory.find(first)).grams, WithinAbs(990., 1e-9));
    CHECK_THAT(Spools::remaining(*inventory.find(third)).grams, WithinAbs(960., 1e-9));
    // Told again, the job is still counted once.
    CHECK(SpoolJobs::record_job(inventory, job, 9) == 2);
    CHECK_THAT(Spools::remaining(*inventory.find(third)).grams, WithinAbs(960., 1e-9));
}

TEST_CASE("A job that stopped early counts for the part of its filament the printer says was fed", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    const int number = spool_in_slot(inventory, 0, 1000.);
    U1Jobs::Job job  = job_of(U1Jobs::State::Cancelled, {80.}, {0});
    // The printer has not said yet: nothing is written, so a later answer is not a second count.
    CHECK(SpoolJobs::record_job(inventory, job, 5) == 0);
    CHECK(inventory.find(number)->uses.empty());
    job.fed_part = 0.25;
    CHECK(SpoolJobs::record_job(inventory, job, 6) == 1);
    CHECK_THAT(Spools::remaining(*inventory.find(number)).grams, WithinAbs(980., 1e-9));
    // A failed job goes the same way, and a figure past the whole file is the whole file.
    U1Jobs::Job failed = job_of(U1Jobs::State::Failed, {100.}, {0});
    failed.fed_part    = 1.4;
    CHECK(SpoolJobs::record_job(inventory, failed, 7) == 1);
    CHECK_THAT(Spools::remaining(*inventory.find(number)).grams, WithinAbs(880., 1e-9));
}

TEST_CASE("A spool added from a printer's report holds what the printer reports", "[SpoolJobs]")
{
    Spools::Spool counted;
    SpoolJobs::set_reported_weight(counted, 1000., 741.5, 9);
    CHECK_THAT(counted.initial_g, WithinAbs(1000., 1e-9));
    const Spools::Remaining left = Spools::remaining(counted);
    CHECK_THAT(left.grams, WithinAbs(741.5, 1e-9));
    // The printer's count is an estimate, never shown as a weighing.
    CHECK_FALSE(left.measured);

    // A tag gives what the spool held when new and nothing about now.
    Spools::Spool tagged;
    SpoolJobs::set_reported_weight(tagged, 750., 0., 9);
    CHECK_THAT(Spools::remaining(tagged).grams, WithinAbs(750., 1e-9));
    CHECK(tagged.uses.empty());

    // Nothing reported: the assumed figure.
    Spools::Spool unknown;
    SpoolJobs::set_reported_weight(unknown, 0., 0., 9);
    CHECK_THAT(unknown.initial_g, WithinAbs(SpoolJobs::ASSUMED_FULL_G, 1e-9));
    // A count with no figure for the full spool is measured against the assumed one.
    Spools::Spool part;
    SpoolJobs::set_reported_weight(part, 0., 300., 9);
    CHECK_THAT(Spools::remaining(part).grams, WithinAbs(300., 1e-9));
}

TEST_CASE("A job that is still printing, or never started, takes nothing", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    const int number = spool_in_slot(inventory, 0, 1000.);
    CHECK(SpoolJobs::record_job(inventory, job_of(U1Jobs::State::Printing, {80.}, {0}), 5) == 0);
    CHECK(SpoolJobs::record_job(inventory, job_of(U1Jobs::State::Uploaded, {80.}, {0}), 5) == 0);
    U1Jobs::Job refused = job_of(U1Jobs::State::Failed, {80.}, {0});
    refused.start_sent  = false;
    refused.fed_part    = 0.5;
    CHECK(SpoolJobs::record_job(inventory, refused, 5) == 0);
    CHECK_THAT(Spools::remaining(*inventory.find(number)).grams, WithinAbs(1000., 1e-9));
}

TEST_CASE("A job is taken from the spools of its own printer, however that printer's address is written", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    const int here  = spool_in_slot(inventory, 0, 1000., "192.168.1.243");
    const int other = spool_in_slot(inventory, 0, 1000., "192.168.1.114");
    CHECK(SpoolJobs::record_job(inventory, job_of(U1Jobs::State::Completed, {30.}, {0}, "192.168.1.243:7125"), 5) == 1);
    CHECK_THAT(Spools::remaining(*inventory.find(here)).grams, WithinAbs(970., 1e-9));
    CHECK_THAT(Spools::remaining(*inventory.find(other)).grams, WithinAbs(1000., 1e-9));
}

TEST_CASE("A print that takes more than a spool holds is named as a shortfall", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    const int low = spool_in_slot(inventory, 1, 30.);
    spool_in_slot(inventory, 2, 1000.);
    // Filaments 1 and 3 both print from slot 2: together they need more than is left there.
    const std::vector<SpoolJobs::Shortfall> found = SpoolJobs::shortfalls(inventory, "192.168.1.243", {20., 500., 15.}, {1, 2, 1});
    REQUIRE(found.size() == 1);
    CHECK(found[0].spool == low);
    CHECK(found[0].filament == 0);
    CHECK_THAT(found[0].needed_g, WithinAbs(35., 1e-9));
    CHECK_THAT(found[0].left_g, WithinAbs(30., 1e-9));
    CHECK_FALSE(found[0].measured);
}

TEST_CASE("A slot with no spool recorded gives no shortfall", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    spool_in_slot(inventory, 0, 1000.);
    CHECK(SpoolJobs::shortfalls(inventory, "192.168.1.243", {20., 5000.}, {0, 3}).empty());
    CHECK(SpoolJobs::shortfalls(inventory, "192.168.1.99", {5000.}, {0}).empty());
}

TEST_CASE("A job takes from the spools noted when it was sent, wherever they are when it ends", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    const int started_on = spool_in_slot(inventory, 0, 1000.);
    U1Jobs::Job job      = job_of(U1Jobs::State::Completed, {40.}, {0});
    job.spools           = SpoolJobs::spools_for(inventory, job.printer, job.toolhead_map);
    REQUIRE(job.spools == std::vector<int>{started_on});
    // During the print the person takes that spool out and records another in the toolhead.
    inventory.move(started_on, Spools::Location{Spools::Location::Kind::Storage, "shelf", -1});
    const int put_in_later = spool_in_slot(inventory, 0, 1000.);
    CHECK(SpoolJobs::record_job(inventory, job, 5) == 1);
    CHECK_THAT(Spools::remaining(*inventory.find(started_on)).grams, WithinAbs(960., 1e-9));
    CHECK_THAT(Spools::remaining(*inventory.find(put_in_later)).grams, WithinAbs(1000., 1e-9));
}

TEST_CASE("A job sent with no spool noted for a toolhead takes nothing for it, even if one is recorded there later", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    spool_in_slot(inventory, 1, 1000.);
    U1Jobs::Job job = job_of(U1Jobs::State::Completed, {40.}, {0});
    job.spools      = SpoolJobs::spools_for(inventory, job.printer, job.toolhead_map);
    REQUIRE(job.spools == std::vector<int>{0});
    const int later = spool_in_slot(inventory, 0, 1000.);
    CHECK(SpoolJobs::record_job(inventory, job, 5) == 0);
    CHECK_THAT(Spools::remaining(*inventory.find(later)).grams, WithinAbs(1000., 1e-9));
}

TEST_CASE("A filament the job's spool note does not reach goes by the spool in its slot", "[SpoolJobs]")
{
    Spools::Inventory inventory;
    const int first  = spool_in_slot(inventory, 0, 1000.);
    const int second = spool_in_slot(inventory, 1, 1000.);
    U1Jobs::Job job  = job_of(U1Jobs::State::Completed, {10., 20.}, {0, 1});
    job.spools       = {first}; // a note that covers the first filament only
    CHECK(SpoolJobs::record_job(inventory, job, 5) == 2);
    CHECK_THAT(Spools::remaining(*inventory.find(first)).grams, WithinAbs(990., 1e-9));
    CHECK_THAT(Spools::remaining(*inventory.find(second)).grams, WithinAbs(980., 1e-9));
}
