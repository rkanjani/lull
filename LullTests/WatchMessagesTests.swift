import BedJetKit
import XCTest
@testable import Lull

/// What the watch and the phone say to each other. The Android apps send the same bytes, pinned by the same strings in
/// `WatchMessagesTest.kt`, so a change to either side shows up in both.
final class WatchMessagesTests: XCTestCase {
    private let side = UUID(uuidString: "6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10")!

    func testCommandsEncodeTheSameAsTheAndroidApps() {
        let cool = RemotePreset(mode: .cool, minutes: 30, temperature: 44, fanPercent: 40)
        let cases: [(WatchCommand, String)] = [
            (.startNight(side), #"{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","kind":"startNight"}"#),
            (.startNight(nil), #"{"kind":"startNight"}"#),
            (.endNight(side), #"{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","kind":"endNight"}"#),
            (.remote(cool, side),
             #"{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","fanPercent":40,"kind":"remote","minutes":30,"mode":"cool","temperature":44}"#),
            (.remote(RemotePreset(mode: .extendedHeat, minutes: 90, temperature: 58, fanPercent: 25), nil),
             #"{"fanPercent":25,"kind":"remote","minutes":90,"mode":"gentleHeat","temperature":58}"#),
            // From the watch's Remote, which says what was changed on it.
            (.remote(cool, side, changed: [.time, .fan]),
             #"{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","changed":["fan","time"],"fanPercent":40,"kind":"remote","minutes":30,"mode":"cool","temperature":44}"#),
            (.refresh, #"{"kind":"refresh"}"#),
        ]
        for (command, json) in cases {
            XCTAssertEqual(String(decoding: command.data, as: UTF8.self), json)
            XCTAssertEqual(WatchCommand(data: Data(json.utf8)), command)
        }
    }

    func testRepliesEncodeTheSameAsTheAndroidApps() {
        XCTAssertEqual(String(decoding: WatchReply.done.data, as: UTF8.self), #"{"ok":true}"#)
        let failed = WatchReply.failed("Couldn't reach your BedJet.")
        XCTAssertEqual(String(decoding: failed.data, as: UTF8.self), #"{"message":"Couldn't reach your BedJet.","ok":false}"#)
        XCTAssertEqual(WatchReply(data: failed.data), failed)
    }

    func testOnlyErrandsAreAnswered() {
        XCTAssertTrue(WatchCommand.startNight(nil).isAnswered)
        XCTAssertTrue(WatchCommand.endNight(side).isAnswered)
        XCTAssertTrue(WatchCommand.remote(RemotePreset(mode: .cool, minutes: 30, temperature: 44, fanPercent: 40), nil).isAnswered)
        // The watch takes any reply as its errand's, so a refresh sent while one runs mustn't answer it.
        XCTAssertFalse(WatchCommand.refresh.isAnswered)
    }

    func testASettingALaterVersionAddsIsLeftOut() {
        let cool = RemotePreset(mode: .cool, minutes: 30, temperature: 44, fanPercent: 40)
        let json = #"{"changed":["fan","sparkle"],"fanPercent":40,"kind":"remote","minutes":30,"mode":"cool","temperature":44}"#
        XCTAssertEqual(WatchCommand(data: Data(json.utf8)), .remote(cool, nil, changed: [.fan]))
    }

    func testIgnoresWhatItDoesntUnderstand() {
        XCTAssertNil(WatchCommand(data: Data(#"{"kind":"dance"}"#.utf8)))
        XCTAssertNil(WatchCommand(data: Data(#"{"kind":"remote","mode":"sauna","minutes":30,"temperature":44,"fanPercent":40}"#.utf8)))
        XCTAssertNil(WatchCommand(data: Data("not json".utf8)))
        // A BedJet named but unreadable isn't taken as none named, which would mean the one on screen.
        XCTAssertNil(WatchCommand(data: Data(#"{"bedJet":"left side","kind":"endNight"}"#.utf8)))
        XCTAssertNil(WatchCommand(data: Data(#"{"bedJet":42,"kind":"startNight"}"#.utf8)))
        XCTAssertNil(WatchCommand(data: Data(#"{"bedJet":null,"kind":"endNight"}"#.utf8)))
    }
}
