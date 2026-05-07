// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "yt-probe",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "yt-probe", targets: ["yt-probe"]),
        .executable(name: "yt-play", targets: ["yt-play"])
    ],
    dependencies: [
        .package(url: "https://github.com/alexeichhorn/YouTubeKit", exact: "0.4.8")
    ],
    targets: [
        .executableTarget(
            name: "yt-probe",
            dependencies: [
                .product(name: "YouTubeKit", package: "YouTubeKit")
            ]
        ),
        .executableTarget(
            name: "yt-play",
            dependencies: [
                .product(name: "YouTubeKit", package: "YouTubeKit")
            ]
        )
    ]
)
