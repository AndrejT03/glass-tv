import Foundation

/// The app's words, in the TV's language: Glass on the TV is Macedonian or English, and the app
/// follows it (before a TV is known, the language the TV last had, else the iPhone's). Names of
/// what the TV defines (picture presets, colours, the settings' choices) come from the TV itself.
struct Strings: Sendable {
    let connectTitle: String
    let connectHint: String
    let searching: String
    let enterAddress: String
    let addressPlaceholder: String
    let addressHint: String
    let connect: String
    let cancel: String
    let pairTitle: String
    /// %@ is the TV's name.
    let pairHint: String
    let wrongCode: String
    let codeOver: String
    let newCode: String
    let cantReach: String
    let noKeychain: String
    let controls: String
    let inputs: String
    let screenSaver: String
    let settings: String
    let powerOff: String
    let powerOffConfirm: String
    let powerOn: String
    let controlCenter: String
    let quickSettings: String
    let restart: String
    let restartConfirm: String
    let cantRestart: String
    let notOnTV: String
    let needsService: String
    let sleepTimer: String
    let on: String
    let off: String
    let volume: String
    let muted: String
    let picture: String
    let brightness: String
    let light: String
    let connecting: String
    let needsBluetooth: String
    let stripAway: String
    let setDefault: String
    let setDefaultConfirm: String
    let performance: String
    let internet: String
    let memory: String
    let network: String
    let noAnswer: String
    let lastApp: String
    let apps: String
    let rename: String
    let renameHint: String
    let save: String
    let resetName: String
    let nothingOnIt: String
    let onScreen: String
    let startNow: String
    let saverShowing: String
    let previous: String
    let next: String
    let stop: String
    let look: String
    let general: String
    let appBackground: String
    let likeTV: String
    let yourPhoto: String
    let choosePhoto: String
    let black: String
    let accent: String
    let accentNames: [String: String]
    let renameTV: String
    let tvNameHint: String
    let gradients: String
    let remove: String
    let blendNames: [String: String]
    let openOnTV: String
    let androidSettings: String
    let wifi: String
    let bluetooth: String
    let soundMode: String
    let speakers: String
    let pictureSettings: String
    let gameMaster: String
    let cec: String
    let tv: String
    let home: String
    let name: String
    let address: String
    let forget: String
    let forgetConfirm: String
    let reconnecting: String
    /// Off the TV's Wi-Fi: no Wi-Fi at all, or another one; what to do; the banner's short line.
    let wifiOff: String
    let wifiOther: String
    let wifiHint: String
    let wifiBanner: String
    let saverGone: String
    let noLastApp: String
    let cantDo: String
    let widgets: String
    let widgetsReady: String
    let widgetsNoGroup: String
    let widgetsNoGroupHint: String
    let widgetsWaiting: String
    let widgetsWaitingHint: String

    /// What to say when the TV refused something, by Glass's name for the reason.
    func refusal(_ reason: String) -> String {
        switch reason {
        case "needs-service": needsService
        case "needs-bluetooth": needsBluetooth
        case "no-saver": saverGone
        case "no-last-app": noLastApp
        case "cant-restart": cantRestart
        case "not-on-tv": notOnTV
        default: cantDo
        }
    }

    static func of(_ language: String) -> Strings { language == "en" ? .en : .mk }

    static let mk = Strings(
        connectTitle: "Поврзи се со ТВ-то",
        connectHint: "iPhone-от и ТВ-то треба да се на истата Wi-Fi мрежа, а Glass да е на ТВ-то.",
        searching: "Го барам ТВ-то…",
        enterAddress: "Внеси адреса",
        addressPlaceholder: "192.168.100.125",
        addressHint: "Адресата на ТВ-то на домашната мрежа.",
        connect: "Поврзи",
        cancel: "Откажи",
        pairTitle: "Внеси го кодот",
        pairHint: "Кодот е на екранот на „%@“.",
        wrongCode: "Погрешен код. Обиди се пак.",
        codeOver: "Кодот истече.",
        newCode: "Нов код",
        cantReach: "Не можам да стигнам до ТВ-то.",
        noKeychain: "iPhone-от не го зачува клучот за ТВ-то.",
        controls: "Контроли",
        inputs: "Влезови",
        screenSaver: "Screen saver",
        settings: "Поставки",
        powerOff: "Исклучи",
        powerOffConfirm: "Да се исклучи ТВ-то?",
        powerOn: "Вклучи",
        controlCenter: "Контрол центар",
        quickSettings: "Брзи поставки",
        restart: "Рестартирај",
        restartConfirm: "Да се рестартира ТВ-то?",
        cantRestart: "Не најдов Restart во менито за напојување на ТВ-то.",
        notOnTV: "Тоа го нема на ТВ-то.",
        needsService: "Вклучи „Glass“ во Пристапност на ТВ-то.",
        sleepTimer: "Тајмер за спиење",
        on: "Вклучено",
        off: "Исклучено",
        volume: "Звук",
        muted: "Без звук",
        picture: "Слика",
        brightness: "Осветленост",
        light: "Светло",
        connecting: "Се поврзува…",
        needsBluetooth: "Дозволи Bluetooth за Glass на ТВ-то.",
        stripAway: "Лентата не се јавува",
        setDefault: "Направи ја стандардна",
        setDefaultConfirm: "Оваа боја ќе биде „Стандардна“ за светлото.",
        performance: "Перформанси",
        internet: "Интернет",
        memory: "Меморија",
        network: "Мрежа",
        noAnswer: "Нема",
        lastApp: "Последна апликација",
        apps: "Апликации",
        rename: "Преименувај",
        renameHint: "Како го викате дома?",
        save: "Зачувај",
        resetName: "Врати го името",
        nothingOnIt: "ништо не е приклучено",
        onScreen: "На екранот",
        startNow: "Пушти сега",
        saverShowing: "Screen saver-от е на екранот",
        previous: "Претходна",
        next: "Следна",
        stop: "Стоп",
        look: "Изглед",
        general: "Општо",
        appBackground: "Позадина на апликацијата",
        likeTV: "Како ТВ-то",
        yourPhoto: "Твоја слика",
        choosePhoto: "Додај слика",
        black: "Pure Black",
        accent: "Акцент",
        accentNames: ["blue": "Сина", "indigo": "Индиго", "purple": "Виолетова", "pink": "Розова", "red": "Црвена", "orange": "Портокалова", "yellow": "Жолта", "green": "Зелена", "teal": "Тиркизна", "graphite": "Графит"],
        renameTV: "Преименувај го ТВ-то",
        tvNameHint: "Името се чува на ТВ-то. Празно му го враќа неговото.",
        gradients: "Gradients",
        remove: "Отстрани",
        blendNames: ["coral": "Корал", "lavender": "Лаванда", "ocean": "Океан", "amber": "Килибар", "meadow": "Ливада", "night": "Ноќ"],
        openOnTV: "Отвори на ТВ-то",
        androidSettings: "Android поставки",
        wifi: "Wi-Fi",
        bluetooth: "Bluetooth",
        soundMode: "Режим на звук",
        speakers: "Звучници",
        pictureSettings: "Поставки за слика",
        gameMaster: "Game Master",
        cec: "HDMI-CEC",
        tv: "ТВ",
        home: "Почетен екран",
        name: "Име",
        address: "Адреса",
        forget: "Заборави го ТВ-то",
        forgetConfirm: "За да го контролираш пак, ќе треба нов код од ТВ-то.",
        reconnecting: "Се поврзувам со ТВ-то…",
        wifiOff: "Поврзи се на Wi-Fi",
        wifiOther: "Ова не е Wi\u{2011}Fi\u{2011}то на ТВ\u{2011}то",
        wifiHint: "За да го контролираш ТВ\u{2011}то, iPhone-от треба да е на истата Wi-Fi мрежа како ТВ\u{2011}то.",
        wifiBanner: "Поврзи се на Wi\u{2011}Fi\u{2011}то на ТВ\u{2011}то",
        saverGone: "Screen saver-от веќе не е на екранот.",
        noLastApp: "Нема последна апликација.",
        cantDo: "ТВ-то не можеше да го направи тоа.",
        widgets: "Widget-и",
        widgetsReady: "Подготвени",
        widgetsNoGroup: "Без App Group",
        widgetsNoGroupHint: "Glass е потпишан без App Group дозволата, па widget-ите не можат да го видат ТВ-то. Потпиши го пак со алатка што ја задржува (Sideloadly, AltStore) или инсталирај го преку Xcode.",
        widgetsWaiting: "Не се јавиле",
        widgetsWaitingHint: "Додај widget на почетниот екран. Ако веќе има и тој бара да се поврзеш, widget-ите се потпишани без истата App Group како апликацијата."
    )

    static let en = Strings(
        connectTitle: "Connect to Your TV",
        connectHint: "Your iPhone and the TV need to be on the same Wi-Fi network, with Glass on the TV.",
        searching: "Looking for your TV…",
        enterAddress: "Enter Address",
        addressPlaceholder: "192.168.100.125",
        addressHint: "The TV's address on your home network.",
        connect: "Connect",
        cancel: "Cancel",
        pairTitle: "Enter the Code",
        pairHint: "The code is on the screen of “%@”.",
        wrongCode: "Wrong code. Try again.",
        codeOver: "The code has expired.",
        newCode: "New Code",
        cantReach: "Can't reach the TV.",
        noKeychain: "The iPhone couldn't keep the TV's key.",
        controls: "Controls",
        inputs: "Inputs",
        screenSaver: "Screen Saver",
        settings: "Settings",
        powerOff: "Power Off",
        powerOffConfirm: "Turn off the TV?",
        powerOn: "Turn On",
        controlCenter: "Control Center",
        quickSettings: "Quick Settings",
        restart: "Restart",
        restartConfirm: "Restart the TV?",
        cantRestart: "Couldn't find Restart in the TV's power menu.",
        notOnTV: "The TV doesn't offer that.",
        needsService: "Turn on Glass in the TV's Accessibility settings.",
        sleepTimer: "Sleep Timer",
        on: "On",
        off: "Off",
        volume: "Sound",
        muted: "Muted",
        picture: "Picture",
        brightness: "Brightness",
        light: "Light",
        connecting: "Connecting…",
        needsBluetooth: "Allow Glass to use Bluetooth on the TV.",
        stripAway: "The strip isn't answering",
        setDefault: "Make It Default",
        setDefaultConfirm: "This colour becomes the light's Default.",
        performance: "Performance",
        internet: "Internet",
        memory: "Memory",
        network: "Network",
        noAnswer: "None",
        lastApp: "Last App",
        apps: "Apps",
        rename: "Rename",
        renameHint: "What do you call it at home?",
        save: "Save",
        resetName: "Restore Name",
        nothingOnIt: "nothing plugged in",
        onScreen: "On Screen",
        startNow: "Start Now",
        saverShowing: "The screen saver is on",
        previous: "Previous",
        next: "Next",
        stop: "Stop",
        look: "Look",
        general: "General",
        appBackground: "App Background",
        likeTV: "Like the TV",
        yourPhoto: "Your Photo",
        choosePhoto: "Add Photo",
        black: "Pure Black",
        accent: "Accent",
        accentNames: ["blue": "Blue", "indigo": "Indigo", "purple": "Purple", "pink": "Pink", "red": "Red", "orange": "Orange", "yellow": "Yellow", "green": "Green", "teal": "Teal", "graphite": "Graphite"],
        renameTV: "Rename the TV",
        tvNameHint: "The name is kept on the TV. Leave it empty for the TV's own.",
        gradients: "Gradients",
        remove: "Remove",
        blendNames: ["coral": "Coral", "lavender": "Lavender", "ocean": "Ocean", "amber": "Amber", "meadow": "Meadow", "night": "Night"],
        openOnTV: "Open on the TV",
        androidSettings: "Android Settings",
        wifi: "Wi-Fi",
        bluetooth: "Bluetooth",
        soundMode: "Sound Mode",
        speakers: "Speakers",
        pictureSettings: "Picture Settings",
        gameMaster: "Game Master",
        cec: "HDMI-CEC",
        tv: "TV",
        home: "Home Screen",
        name: "Name",
        address: "Address",
        forget: "Forget This TV",
        forgetConfirm: "To control it again, you'll need a new code from the TV.",
        reconnecting: "Reconnecting to the TV…",
        wifiOff: "Connect to Wi-Fi",
        wifiOther: "This Isn't the TV's Wi-Fi",
        wifiHint: "To control the TV, your iPhone needs to be on the same Wi-Fi network as the TV.",
        wifiBanner: "Join the TV's Wi-Fi to control it",
        saverGone: "The screen saver isn't on any more.",
        noLastApp: "There's no last app.",
        cantDo: "The TV couldn't do that.",
        widgets: "Widgets",
        widgetsReady: "Ready",
        widgetsNoGroup: "No App Group",
        widgetsNoGroupHint: "Glass was signed without its App Group, so the widgets can't see the TV. Sign it again with a tool that keeps it (Sideloadly, AltStore) or install it from Xcode.",
        widgetsWaiting: "Not heard from",
        widgetsWaitingHint: "Add a widget to the Home Screen. If there is one and it asks you to connect, the widgets were signed without the app's App Group."
    )
}
