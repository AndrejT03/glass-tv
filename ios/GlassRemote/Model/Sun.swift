import Foundation

/// Where the sun is: degrees above the horizon, and the compass bearing (0 north, 90 east).
struct SunPosition: Equatable, Sendable {
    var altitude: Double
    var azimuth: Double

    /// Where the sun is at [date] seen from [latitude], [longitude]: NOAA's solar position equations,
    /// as Glass on the TV works it out (screensaver/DynamicWallpapers.kt), so the picker shows the
    /// frame the TV does.
    init(at date: Date, latitude: Double, longitude: Double) {
        func rad(_ d: Double) -> Double { d * .pi / 180 }
        func deg(_ r: Double) -> Double { r * 180 / .pi }
        func wrap(_ x: Double, _ m: Double) -> Double { let r = x.truncatingRemainder(dividingBy: m); return r < 0 ? r + m : r }

        let ms = date.timeIntervalSince1970 * 1000
        let julianDay = ms / 86_400_000 + 2_440_587.5
        let t = (julianDay - 2_451_545) / 36_525
        let meanLong = wrap(280.46646 + t * (36_000.76983 + t * 0.0003032), 360)
        let anomaly = rad(357.52911 + t * (35_999.05029 - 0.0001537 * t))
        let eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        let center = sin(anomaly) * (1.914602 - t * (0.004817 + 0.000014 * t))
            + sin(2 * anomaly) * (0.019993 - 0.000101 * t) + sin(3 * anomaly) * 0.000289
        let omega = rad(125.04 - 1934.136 * t)
        let lambda = rad(meanLong + center - 0.00569 - 0.00478 * sin(omega))
        let obliquity = rad(23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60 + 0.00256 * cos(omega))
        let declination = asin(sin(obliquity) * sin(lambda))
        let y = pow(tan(obliquity / 2), 2)
        let l0 = rad(meanLong)
        // The equation of time, in minutes.
        let eqTime = 4 * deg(
            y * sin(2 * l0) - 2 * eccentricity * sin(anomaly) + 4 * eccentricity * y * sin(anomaly) * cos(2 * l0)
                - 0.5 * y * y * sin(4 * l0) - 1.25 * eccentricity * eccentricity * sin(2 * anomaly)
        )
        let utcMinutes = wrap(ms, 86_400_000) / 60_000
        let solarMinutes = wrap(utcMinutes + eqTime + 4 * longitude, 1440)
        let hourAngle = rad(solarMinutes / 4 - 180)
        let lat = rad(latitude)
        let cosZenith = sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)
        let zenith = acos(min(max(cosZenith, -1), 1))
        let azimuth = deg(atan2(sin(hourAngle), cos(hourAngle) * sin(lat) - tan(declination) * cos(lat))) + 180
        self.altitude = 90 - deg(zenith)
        self.azimuth = wrap(azimuth, 360)
    }
}

/// The part of the day the sun says it is, as the TV's caption names it.
enum DayPart: Int, CaseIterable, Sendable {
    case night, dawn, sunrise, morning, midday, afternoon, sunset, dusk

    init(_ sun: SunPosition) {
        let morning = sun.azimuth < 180
        self = switch sun.altitude {
        case ..<(-12): .night
        case ..<(-4): morning ? .dawn : .dusk
        case ..<6: morning ? .sunrise : .sunset
        default: (155...205).contains(sun.azimuth) ? .midday : morning ? .morning : .afternoon
        }
    }

    /// Its name, in the TV's language (the TV's caption's words).
    func name(_ language: String) -> String {
        let names = language == "en"
            ? ["Night", "Dawn", "Sunrise", "Morning", "Midday", "Afternoon", "Sunset", "Dusk"]
            : ["Ноќ", "Зора", "Изгрев", "Утро", "Пладне", "Попладне", "Зајдисонце", "Самрак"]
        return names[rawValue]
    }

    var symbol: String {
        switch self {
        case .night: "moon.stars.fill"
        case .dawn, .dusk: "sun.horizon.fill"
        case .sunrise: "sunrise.fill"
        case .sunset: "sunset.fill"
        case .morning, .afternoon: "sun.min.fill"
        case .midday: "sun.max.fill"
        }
    }
}
