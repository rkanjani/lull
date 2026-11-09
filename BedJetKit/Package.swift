// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BedJetKit",
    platforms: [.iOS(.v17), .macOS(.v14), .watchOS(.v10)],
    products: [
        .library(name: "BedJetKit", targets: ["BedJetKit"]),
        .executable(name: "bedjetctl", targets: ["bedjetctl"]),
    ],
    targets: [
        .target(name: "BedJetKit"),
        .executableTarget(name: "bedjetctl", dependencies: ["BedJetKit"]),
        .testTarget(name: "BedJetKitTests", dependencies: ["BedJetKit"]),
    ]
)
