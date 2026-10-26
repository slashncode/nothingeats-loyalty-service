// The loyalty service's gRPC contract: protos and generated Kotlin stubs. Consumers include this
// repository as a composite build and depend on io.github.slashncode.nothingeats.loyalty:grpc-api.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.grpc")
}

group = "io.github.slashncode.nothingeats.loyalty"

// A service may use several contracts; their jars must not all be called grpc-api.jar.
base {
    archivesName = "loyalty-grpc-api"
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
}
