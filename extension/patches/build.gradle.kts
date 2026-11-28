// The Morphe Gradle plugin includes a :patches project by convention for every build that applies
// it. This nested build only produces the extension dex, so the directory exists with no sources;
// the patch sources themselves live in the Kotlin project at the repository root and are compiled
// by build.sh.
