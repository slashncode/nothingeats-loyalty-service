// The loyalty service's gRPC contract: protos and generated Kotlin stubs. Consumers include this
// repository as a composite build and depend on io.github.slashncode.nothingeats.loyalty:grpc-api.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.grpc")
}

group = "io.github.slashncode.nothingeats.loyalty"

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
}
