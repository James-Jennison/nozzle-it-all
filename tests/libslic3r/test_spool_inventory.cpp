#include <catch2/catch_all.hpp>

#include "libslic3r/SpoolInventory.hpp"

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>

#include <string>

using namespace Slic3r;
using Catch::Matchers::WithinAbs;
namespace fs = boost::filesystem;

namespace {

struct ScratchFile
{
    fs::path path = fs::temp_directory_path() / fs::unique_path("spools-%%%%-%%%%.json");
    ~ScratchFile()
    {
        boost::system::error_code ec;
        fs::remove(path, ec);
        fs::remove(path.string() + ".tmp", ec);
    }
};

Spools::Spool pla(double grams = 1000.)
{
    Spools::Spool spool;
    spool.name      = "Polymaker PLA";
    spool.vendor    = "Polymaker";
    spool.material  = "PLA";
    spool.color     = "#D93B90";
    spool.initial_g = grams;
    return spool;
}

Spools::Location in_printer(int slot, const std::string &printer = "192.168.1.243")
{
    return Spools::Location{Spools::Location::Kind::Printer, printer, slot};
}

} // namespace

TEST_CASE("Each spool gets a number of its own that is never given again", "[Spools]")
{
    Spools::Inventory inventory;
    const int first  = inventory.add(pla(), 1);
    const int second = inventory.add(pla(), 2);
    CHECK(first == 1);
    CHECK(second == 2);
    inventory.find(second)->archived = true;
    CHECK(inventory.add(pla(), 3) == 3);
}

TEST_CASE("What is left on a spool is its initial amount less what jobs took, as an estimate", "[Spools]")
{
    Spools::Inventory inventory;
    const int number = inventory.add(pla(1000.), 1);
    REQUIRE(inventory.record_use(number, {"job-a", 47.5, false, 10}));
    REQUIRE(inventory.record_use(number, {"job-b", 2.5, false, 20}));
    const Spools::Remaining left = Spools::remaining(*inventory.find(number));
    CHECK_THAT(left.grams, WithinAbs(950., 1e-9));
    CHECK_FALSE(left.measured);
}

TEST_CASE("A weighing replaces the estimate, and later use is taken from the weighed amount", "[Spools]")
{
    Spools::Inventory inventory;
    const int number = inventory.add(pla(1000.), 1);
    REQUIRE(inventory.record_use(number, {"job-a", 100., false, 10}));
    REQUIRE(inventory.weigh(number, 870., 20));
    Spools::Remaining left = Spools::remaining(*inventory.find(number));
    CHECK_THAT(left.grams, WithinAbs(870., 1e-9));
    CHECK(left.measured);
    REQUIRE(inventory.record_use(number, {"job-b", 20., false, 30}));
    left = Spools::remaining(*inventory.find(number));
    CHECK_THAT(left.grams, WithinAbs(850., 1e-9));
    CHECK_FALSE(left.measured);
}

TEST_CASE("A job is counted once for a spool however often its use is recorded", "[Spools]")
{
    Spools::Inventory inventory;
    const int number = inventory.add(pla(1000.), 1);
    REQUIRE(inventory.record_use(number, {"job-a", 30., false, 10}));
    // The job ended further on than first recorded: the entry is replaced, not added to.
    REQUIRE(inventory.record_use(number, {"job-a", 60., false, 99}));
    CHECK(inventory.find(number)->uses.size() == 1);
    CHECK_THAT(Spools::remaining(*inventory.find(number)).grams, WithinAbs(940., 1e-9));
}

TEST_CASE("A job recorded again after a weighing stays weighed in", "[Spools]")
{
    Spools::Inventory inventory;
    const int number = inventory.add(pla(1000.), 1);
    REQUIRE(inventory.record_use(number, {"job-a", 30., false, 10}));
    REQUIRE(inventory.weigh(number, 960., 20));
    REQUIRE(inventory.record_use(number, {"job-a", 35., false, 50}));
    const Spools::Remaining left = Spools::remaining(*inventory.find(number));
    CHECK_THAT(left.grams, WithinAbs(960., 1e-9));
    CHECK(left.measured);
}

TEST_CASE("What is left on a spool never goes below nothing", "[Spools]")
{
    Spools::Inventory inventory;
    const int number = inventory.add(pla(50.), 1);
    REQUIRE(inventory.record_use(number, {"job-a", 80., false, 10}));
    CHECK_THAT(Spools::remaining(*inventory.find(number)).grams, WithinAbs(0., 1e-9));
}

TEST_CASE("A printer slot holds one spool: the one that was there goes to storage", "[Spools]")
{
    Spools::Inventory inventory;
    const int first  = inventory.add(pla(), 1);
    const int second = inventory.add(pla(), 2);
    CHECK(inventory.move(first, in_printer(1)) == 0);
    REQUIRE(inventory.at("192.168.1.243", 1) != nullptr);
    CHECK(inventory.at("192.168.1.243", 1)->number == first);
    CHECK(inventory.move(second, in_printer(1)) == first);
    CHECK(inventory.at("192.168.1.243", 1)->number == second);
    CHECK(inventory.find(first)->location.kind == Spools::Location::Kind::Storage);
    // Another slot and another printer are other places.
    CHECK(inventory.at("192.168.1.243", 2) == nullptr);
    CHECK(inventory.at("192.168.1.114", 1) == nullptr);
}

TEST_CASE("A spool added into a slot that is taken starts in storage", "[Spools]")
{
    Spools::Inventory inventory;
    Spools::Spool     there = pla();
    there.location          = in_printer(0);
    const int first         = inventory.add(there, 1);
    const int second        = inventory.add(there, 2);
    CHECK(inventory.at("192.168.1.243", 0)->number == first);
    CHECK(inventory.find(second)->location.kind == Spools::Location::Kind::Storage);
}

TEST_CASE("An archived spool is not found in a slot or by its tag", "[Spools]")
{
    Spools::Inventory inventory;
    Spools::Spool     spool = pla();
    spool.tags              = {"70:49:8f:e2", "70498FE2", ""};
    spool.location          = in_printer(1);
    const int number        = inventory.add(spool, 1);
    CHECK(inventory.find(number)->tags == std::vector<std::string>{"70498FE2"});
    REQUIRE(inventory.with_tag("70498fe2") != nullptr);
    CHECK(inventory.with_tag("70498fe2")->number == number);
    CHECK(inventory.with_tag("") == nullptr);
    inventory.find(number)->archived = true;
    CHECK(inventory.with_tag("70498FE2") == nullptr);
    CHECK(inventory.at("192.168.1.243", 1) == nullptr);
}

TEST_CASE("A drying session is started once and ended with its notes", "[Spools]")
{
    Spools::Inventory inventory;
    const int number = inventory.add(pla(), 1);
    CHECK_FALSE(inventory.end_drying(number, "nothing to end", 5));
    REQUIRE(inventory.start_drying(number, "S4 left", 10));
    CHECK_FALSE(inventory.start_drying(number, "S4 right", 11));
    REQUIRE(Spools::drying_now(*inventory.find(number)) != nullptr);
    CHECK(Spools::drying_now(*inventory.find(number))->place == "S4 left");
    REQUIRE(inventory.end_drying(number, "six hours at 55", 500));
    CHECK(Spools::drying_now(*inventory.find(number)) == nullptr);
    REQUIRE(inventory.find(number)->drying.size() == 1);
    CHECK(inventory.find(number)->drying[0].end_ms == 500);
    CHECK(inventory.find(number)->drying[0].notes == "six hours at 55");
}

TEST_CASE("The spool inventory reads back from its file as it was written", "[Spools]")
{
    ScratchFile       file;
    Spools::Inventory inventory;
    Spools::Spool     spool = pla(750.);
    spool.tags              = {"SKU34263"};
    spool.location          = in_printer(1);
    spool.notes             = "opened in October";
    const int number        = inventory.add(spool, 100);
    REQUIRE(inventory.record_use(number, {"job-a", 12.5, false, 200}));
    REQUIRE(inventory.weigh(number, 700., 300));
    REQUIRE(inventory.start_drying(number, "S4 left", 400));
    inventory.add(pla(), 500);
    REQUIRE(inventory.save(file.path.string()));

    bool                    damaged = true;
    const Spools::Inventory read    = Spools::Inventory::load(file.path.string(), &damaged);
    CHECK_FALSE(damaged);
    REQUIRE(read.spools().size() == 2);
    const Spools::Spool *back = read.find(number);
    REQUIRE(back != nullptr);
    CHECK(back->name == "Polymaker PLA");
    CHECK(back->color == "#D93B90");
    CHECK(back->tags == std::vector<std::string>{"SKU34263"});
    CHECK(back->location == in_printer(1));
    CHECK(back->notes == "opened in October");
    CHECK(back->created_ms == 100);
    REQUIRE(back->uses.size() == 1);
    CHECK(back->uses[0].job_id == "job-a");
    CHECK_THAT(Spools::remaining(*back).grams, WithinAbs(700., 1e-9));
    CHECK(Spools::drying_now(*back) != nullptr);
    // Numbers go on from where they were, also after a restart.
    Spools::Inventory again = read;
    CHECK(again.add(pla(), 600) == 3);
}

TEST_CASE("A missing inventory file is an empty inventory; a damaged one is said to be damaged", "[Spools]")
{
    ScratchFile file;
    bool        damaged = true;
    CHECK(Spools::Inventory::load(file.path.string(), &damaged).spools().empty());
    CHECK_FALSE(damaged);
    {
        boost::nowide::ofstream out(file.path.string());
        out << "{ not json";
    }
    CHECK(Spools::Inventory::load(file.path.string(), &damaged).spools().empty());
    CHECK(damaged);
    {
        boost::nowide::ofstream out(file.path.string(), std::ios::trunc);
        out << R"({"format": 1, "spools": [{"number": 1, "name": "a"}, {"number": 1, "name": "the same number again"}]})";
    }
    CHECK(Spools::Inventory::load(file.path.string(), &damaged).spools().empty());
    CHECK(damaged);
}

TEST_CASE("A change to the inventory is read, made and written under one lock", "[Spools]")
{
    ScratchFile file;
    CHECK(Spools::change(file.path.string(), [](Spools::Inventory &inventory) { return inventory.add(pla(), 1) == 1; }) == Spools::Changed::Saved);
    CHECK(Spools::change(file.path.string(), [](Spools::Inventory &inventory) { return inventory.weigh(1, 900., 2); }) == Spools::Changed::Saved);
    // A change that says no leaves the file as it was.
    CHECK(Spools::change(file.path.string(), [](Spools::Inventory &inventory) {
              inventory.add(pla(), 3);
              return false;
          }) == Spools::Changed::NotChanged);
    const Spools::Inventory read = Spools::Inventory::load(file.path.string());
    REQUIRE(read.spools().size() == 1);
    CHECK_THAT(Spools::remaining(read.spools()[0]).grams, WithinAbs(900., 1e-9));
    boost::system::error_code ec;
    fs::remove(file.path.string() + ".lock", ec);
}

TEST_CASE("A damaged inventory file is not changed", "[Spools]")
{
    ScratchFile file;
    {
        boost::nowide::ofstream out(file.path.string());
        out << "{ not json";
    }
    CHECK(Spools::change(file.path.string(), [](Spools::Inventory &inventory) { return inventory.add(pla(), 1) > 0; }) == Spools::Changed::Damaged);
    boost::nowide::ifstream in(file.path.string());
    std::string             text;
    std::getline(in, text);
    CHECK(text == "{ not json");
    boost::system::error_code ec;
    fs::remove(file.path.string() + ".lock", ec);
}
