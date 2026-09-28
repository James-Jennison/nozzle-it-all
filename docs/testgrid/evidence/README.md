# Test Grid evidence store

`bundles/` holds verified evidence bundles, each named after its bundle digest by
`./gradlew -q :test-grid:cli --args="store-add --store docs/testgrid/evidence/bundles <bundle.zip>"`. Bundles are
never edited or deleted. `acceptance.json` records which bundles a maintainer accepted or rejected (see
[../MAINTAINER_GUIDE.md](../MAINTAINER_GUIDE.md)).

Empty as of 2026-09-28: no physical Test Grid evidence exists yet. Simulated bundles never go here; the example in
[../examples/](../examples/) shows the format.
