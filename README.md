# Glass — tvOS 26 style launcher for Android TV

A home screen for the TCL Smart TV Pro (Google TV, Android 12) that looks and moves like Apple TV:
Liquid Glass surfaces, a huge Lock Screen style clock, tvOS focus motion and Apple screen savers.

## What's in it

- **Home (tvOS 26)**: six icons in a Liquid Glass dock; the focused app's **Top Shelf** fills the
  screen behind it and dissolves into the next one as you move. The dock is a slab of refracting
  glass rendered for each Top Shelf (`DockGlass`): its rounded rim bends in what lies beyond the
  edge, with a slight colour split, catches light along the top and casts a soft shadow; a glass
  lens, concentric with the icons and the dock, glides behind the focused icon. A glass capsule at the top right
  shows the time and the Wi-Fi signal (live: bars, struck through when offline, a port on Ethernet)
  and opens sideways into the controls when you press Up.
  Moving down scrolls into the full grid and the background turns into light frosted glass over
  the Top Shelf (its colours show through, with a sheen across the pane), with the top row on a
  glass shelf and dark labels. Coming back from an app,
  the home screen zooms back into place.
- **Designed Top Shelves** for the 29 apps on the TV (`preview/top-shelf-designed.jpg`): each is a
  board on the Claude Design canvas in `design/canvas` — the app's real logo on a backdrop in the
  spirit of its tvOS Top Shelf, in tvOS proportions (a 1920x720 picture mirrored and blurred down
  to fill the screen). `tools/export_topshelf.py` renders the boards into
  `app/src/main/assets/topshelf/<package>.jpg`, which the launcher uses before anything else.
  TizenTube (a YouTube client) keeps its own tile (made from its 640x360 banner) and gets
  YouTube's Top Shelf (`data/ArtAliases.kt`). YouTube's board is YouTube's own tvOS Top Shelf, the
  paper-collage one (blue disc with a mint scribble, coral and yellow wedges, the green leaf, the
  blue band), traced shape by shape from a photo of an Apple TV, its crayon strokes made with SVG
  turbulence filters; the art runs down to 800 px before the mirror and blur, as on tvOS 26. Stremio's
  logo, which Commons doesn't have, is redrawn as vectors from Stremio's own 670 px logo
  (`design/vectors/stremio.svg`, see SOURCES.md).
- **Top Shelf for any other app**: Apple TV apps supply full-screen Top Shelf art; Android TV apps
  can't (Google's launcher channels are closed to other launchers), but every TV app has a banner —
  its logo on its brand colours, usually 320x180. The launcher rebuilds it full-screen: the brand
  background is modelled without the logo and stretched to the edges, and the logo is lifted out as
  an alpha mask and upscaled with sharpened edges, so it stays crisp at 4x like a vector logo.
  Each takes 40–150 ms and is cached; it is redone when the app updates.
- **Sharp tiles**: a banner is 320x180, but a focused tile is ~310 px wide at 1080p and ~620 px at
  4K. `tools/make_tiles.py` draws the TV apps' tiles at 800x480 (`assets/tiles`) in their banners'
  layout and colours, but from the brands' own vector logos (`design/vectors`, from Wikimedia
  Commons) and with labels typeset again in Roboto; apps with no vector logo get theirs redrawn
  from a distance field. The launcher scales the tiles to the exact size of a focused tile on the
  screen it runs on. The Top Shelf boards use the same vector logos.
- **Big clock home** (setting): the earlier look, with the iOS 26 glass clock over the wallpaper.
- **Apple Aerial wallpaper** (default): a hand-picked frame from one of Apple's aerials, a new one
  each day, like the Apple TV Top Shelf. Only the video index and one keyframe are downloaded
  (~3 MB, HTTP range requests); tomorrow's is fetched ahead. Six generated glass-wave wallpapers
  are there too, and are used until the first photo arrives. Behind the big clock home it is live:
  the aerials themselves play (the calm, wide shots that have a wallpaper frame), dipping through
  black between shots, with the still on screen until the first frame. The glass (dock, clock,
  capsule) samples each shot's own hand-picked frame, so it shows the colours of what is behind
  it (frames copied off the video surface come back as noise from the TV's decoder). It plays
  while the remote rests and is released when another app or the screen saver is in front.
- **Focus motion**: icons lift and grow on a spring, tilt toward the direction you moved, catch a
  moving glare, nudge against edges, and tick softly with each move.
- **Context menu** (hold OK, or the Menu key): Move, App Info, Uninstall. Move makes the other
  icons wiggle while you place the app with the arrows.
- **Top-right controls** (press Up from the top row): Android settings, inputs (HDMI), screen
  saver, launcher settings and the control center, which Up lands on.
- **Control Center** (tvOS 26), from the remote's Settings button (the TCL's remote sends it as
  KEYCODE_NOTIFICATION, Google TV's dashboard key) or its Home button held down (as the TV button on
  Apple TV) over any app, or the capsule's last button: the time and the Control Center symbol over a grid
  of Liquid Glass tiles at the top right. Power Off (puts the TV to sleep) and Performance are big
  squares; Performance is live while it is open: how fast the internet answers (a connection to
  1.1.1.1, or 8.8.8.8, timed each second; the ring lights green under 60 ms, yellow under 150, red
  above or without an answer) and the memory in use as rings, what the network brings in and the
  memory in GB below them. Not the CPU: the TCL lets no app read its load (SELinux closes /proc/stat and /proc/uptime, it has
  no cpuidle, its cores run at full clock under the "performance" governor, and `cpuinfo` is a
  system-only service), nor its temperature. Then capsules: Ambient Light (OK turns the LED strip
  behind the TV on or off) and its Colour (OK, then Left/Right step through the strip's own colour,
  "Default", and ten others, the strip following each at once), Sound (OK, then Left/Right change
  the volume on a level bar) and Wi-Fi (the network's name; OK opens Wi-Fi settings). The strip is
  the Bluetooth one Ambient Sync (`com.example.ambientsync`) turns on with the TV and off two minutes
  after it, duoCo's "ELK-BLEDDM 44" (`Prefs.ambientStrip`): Glass writes the same 9-byte commands to
  its characteristic FFF3 (on `7e0404f00001ff00ef`, off `7e0404000000ff00ef`, a colour
  `7e000503RRGGBB00ef`), connecting when the control center opens and letting go 4 s after it closes,
  as the strip takes one connection at a time. The strip can't say what it is doing, so the tile
  starts from on whenever the screen comes on (Ambient Sync's doing) and shows what Glass told it
  after that. Turned off and on over Bluetooth it also forgets its colour (the household's sky blue
  went that way), so Glass sends the colour with every turn-on, and when the Colour tile opens;
  Default is the household's sky blue, `Prefs.ambientDefault`, which
  `adb shell am broadcast -n com.andrej.glasstv/.controlcenter.AmbientColorReceiver --es rgb 87CEEB`
  sets (the strip turning that colour at once, to match it by eye). Glass needs Bluetooth allowed (Install
  below; on the home screen, OK on the tile asks). A row of round buttons: Bluetooth (Remotes &
  Accessories, where Android TV keeps its Bluetooth devices), screen saver, Last App (back to the app
  used before the one in front, with its icon, its name under the tiles; on the home screen, the
  one used last) and Android settings. The tile in focus
  turns white with dark ink; the tiles drop into place from the top-right corner. On the home screen
  the corner turns into dark frosted glass and the tiles' glass shows the home screen blurred behind
  them (the same 240-pixel CPU blur as the inputs menu); over other apps it is a window of the
  accessibility service on a dark shade, with denser glass, since nothing there can be blurred. A
  short press of Home still goes home: the service takes the button and goes home itself when it is
  let go. Power Off needs that service (only an accessibility service may put the TV to sleep). `--ez demo true` shows made-up performance figures on the emulator.
  When the screen goes off Glass lets the strip go at once, even with the control center still open
  (the remote's Power button never reaches it), so Ambient Sync can turn it off and on again with
  the TV. When Glass turned the strip off before the TV went to sleep (`Prefs.ambientOff`), Ambient
  Sync turns it on again without a colour: 10 s after the screen comes on Glass sends it on and its
  colour (up to four tries, 10 s apart; logcat tag `AmbientLight`).
- **Quick Settings**, from the TCL remote's button right of 0, in place of TCL's own Quick Settings
  bar, over any app (an HDMI picture, YouTube, the home screen): tvOS 26's player controls,
  measured off Apple's own video of them. "Quick Settings" at the left, where tvOS names the show, round Liquid Glass buttons at the right for the
  screens TCL's bar opens, in its order (where the sound goes, Network, Picture Settings, Display &
  Sound, Android settings; the one in focus shows its name over it; TCL's Energy screens are closed
  to other apps, so there is no Energy button), two bars as the player's (each with its figure under the
  playhead): the TV's backlight over its volume ("Sound"; STREAM_MUSIC, without the system's bar; it follows
  the remote's own volume buttons too). Up from the tabs reaches them, Left/Right move them, held for
  bigger steps. Along the bottom, tabs:
  Picture with its preset, Sound with its mode (TCL's `tcl_sys_audio_sound_mode`, on this TV 0 Standard,
  1 Movie, 2 Music, 3 Voice, 4 Game, 5 Sport, 7 Intelligent, read off its pop-up), Sleep Timer, Close
  (with the name of the app in front, where TCL's bar has Game Master), Bluetooth and HDMI-CEC
  (On or Off), filling the row from margin to margin (the room left over shared among them, their
  text a little smaller where it wouldn't fit). Bluetooth and HDMI-CEC are read from `bluetooth_on`
  and `hdmi_control_enabled`; Bluetooth (Remotes & Accessories) and HDMI-CEC (TCL's T-Link, in its
  HDMI-CEC service) open TCL's own screens for them (`com.android.tv.settings.CEC`). Close quits the
  app in front (`quicksettings/AppCloser.kt`): home, then the app's page in the TV's settings (App
  info), where the accessibility service presses Force stop, OK, Clear cache, OK, as the remote
  would, then Back to the home screen; only the system may stop another app or clear its cache, and
  TCL has nothing that does it for one app. The buttons are found by TvSettings' own strings for
  them (`device_apps_app_management_force_stop`, `_clear_cache`, `okay`), so in any language the TV
  is in, with a search by text each time (the service's copy of the views goes stale once the page's
  question has gone). Meanwhile the screen is covered in black with the app's icon and name ("Се
  затвора…", then "Затворена · кешот е исчистен") and the remote waits; 2.5–4 s on the TV. On the
  home screen, over an input (an HDMI picture is the TV's player, no app) and over the TV's settings
  the tab is faint and OK does nothing. TCL's Virtual Keyboard isn't there: TCL's bar sends its TV player
  a key of TCL's own (4056), and no app may send keys to another. Adaptive brightness TCL only shows
  on models with a light sensor, which this one hasn't. Picture and Sleep Timer open their choices
  above the tab as tvOS 26's Control Center lists its sleep timer: glass capsules rising one after
  another, the one that is on with a check mark, the one in focus white. The picture presets are
  the TCL's own for ordinary pictures (Intelligent, Vivid, Standard, Sport, FILMMAKER MODE, Movie):
  Glass reads and sets them, and the backlight, through `tcl_tv_display`, the service of TCL's
  picture-quality library, which the TV's SELinux policy opens to any app, with the calls TCL's
  settings app makes (read out of its code: transaction 27 reads the preset, 182
  `setPictureModeByType(0, EN_APPLY_CURRENT, mode, EN_PICTURE_MODE_FIELD_NORMAL,
  EN_TCL_ACT_EXEC_SAVE)` sets it, 101 and 100 read and set the backlight; `quicksettings/TvPicture.kt`).
  The sound mode and the speakers are only TCL's to set (its audio service wants
  MODIFY_AUDIO_ROUTING): Sound and the speaker button open TCL's own pop-up choices for them
  (its settings app's ShowWindowService), as do the presets of HDR pictures, with
  `exitStartGoogleSettings=false`, so Back closes them back to what was in front (TCL's Settings
  open otherwise). The sleep timer is
  Glass's: the TV goes to sleep through the accessibility service when it runs out, the tab counting
  down meanwhile. Over other apps they sit on a plain shade (nothing there can be blurred); on the
  home screen its bottom turns into dark frosted glass under them, a blur of the home screen itself
  as under the control center and the inputs menu (`ui/home/QuickSettingsFrost.kt`), whole from just
  above the title so the dock never reads through, and its screen saver waits while they are up
  (their keys never reach it). The quick settings go away after 15 s of rest, like tvOS's player controls. The
  button sends TCL's own key code, 4514, which TCL's window manager turns into its bar only on the
  key's way to the app in front (`TclPhoneWindowManagerEx.handleKeyBeforeDispatching`), after
  Glass's accessibility service has taken it. Debug builds also take Bookmark, which
  `tools/remote_keys.py quick` presses on the emulator; `--ez demo true` makes up a preset and a
  backlight there. On the TV, `tools/remote_keys.py --tcl quick` presses the real button: the virtual
  remote is then TCL's own, whose key layout turns its Menu key into 4514.
- The volume keeps the system's bar: Android TV hands Volume Up and Down to the audio service inside
  the window manager's policy, before any app sees them. A Glass bar for Mute alone was tried on
  2026-10-28 and taken out at the user's request (one bar for all three buttons, not two kinds).
- **Inputs menu**, from the remote's Input button over any app (an HDMI picture, YouTube, the home
  screen) or the TV button in the capsule: round glass icons with big white labels and an "INPUTS"
  header in the bottom-left corner, on a dark shade that hugs that corner and fades out toward the
  middle of the screen. It lists the TV's own sockets as the TV lists them (on the TCL: TV, HDMI 1,
  HDMI 2, HDMI 3), under the household's names for them where there is one (`Prefs.inputNames`:
  HDMI 1 is "Кабелска телевизија", the cable TV box, with the port beside it), else the device on
  the port over HDMI-CEC, else the port; tuner inputs that apps add (Google TV, TCL Channel+)
  aren't sockets and are left out. The rows rise into place one after another, the one nearest the
  corner first, each settling softly (~300 ms in all), and sink away together when it closes; the
  focus is a white platter gliding from row to row and taking each row's width, the row centred on
  it in dark ink and its icon concentric with the platter's round end. More, at the end, unfolds
  the inputs with nothing plugged in and the three TV apps opened last (`RecentApps`: the ones
  opened from the home screen, and while the accessibility service below is on, any app that comes
  to the front), each with its own icon in a circle; More fades out as they rise in and the list
  slides up to make room. OK switches the TV to an input through its own player (the passthrough
  channel of an HDMI port, sent with the channel's type: without it the TCL hands the address to its
  web browser), or opens the app, zooming out of its icon; Back closes the menu. With no channels
  scanned on the aerial, the TCL's player shows TCL Channel+ for TV, as its own panel does.
  On the home screen the shade is dark frosted glass, a real blur: a 240-pixel copy of the screen
  blurred and darkened on the CPU (the TV can't blur live), the corner's shape baked into its
  transparency, and drawn only over the part of the screen it reaches; the home screen under it is
  held as one texture while the menu is open. Over other apps it is a plain shade: the menu is a
  window of Glass's accessibility service, which takes no focus and pauses nothing, so an HDMI
  picture keeps playing under it, and an HDMI picture is on a plane of the display hardware that
  Android can't read back, let alone blur. The window is only as wide as the shade (the system
  composites it over the picture on the GPU), and stays composed and laid out while hidden, so it
  comes up at once. The service (InputsKeyService) takes only the Input button from the apps, and
  the arrows, OK and Back while the menu is open, and notes which app is in front; it is turned on
  in Settings → Accessibility → "Glass: Input button". Without it, or where Android lists no inputs,
  the Input button opens the TV's own panel. A debug build started with `--ez demo true` shows
  made-up inputs (and the first apps as recent ones until some are opened), and
  `tools/remote_keys.py input` presses the Input button on the emulator (see "Testing the screen
  savers").
- **Focus motion**: icons lift and grow on a spring, tilt toward the direction you moved, catch a
  moving glare, nudge against edges, and tick softly with each move.
- **Context menu** (hold OK, or the Menu key): Move, App Info, Uninstall. Move makes the other
  icons wiggle while you place the app with the arrows.
- **Top-right controls** (press Up from the top row): screen saver, inputs (HDMI), launcher
  settings, Android settings.
- **Inputs menu**, from the remote's Input button over any app (an HDMI picture, YouTube, the home
  screen) or the TV button in the capsule: round glass icons with big white labels and an "INPUTS"
  header in the bottom-left corner (the device on an HDMI port by its own name over HDMI-CEC,
  "PlayStation 5", with the port beside it), on a dark shade that hugs that corner and fades out
  toward the middle of the screen. The rows rise into place one after another, the one nearest the
  corner first, each settling softly (~300 ms in all), and sink away together when it closes; the
  focus is a white platter gliding from row to row and taking each row's width, the row centred on
  it in dark ink and its icon concentric with the platter's round end. The inputs with
  nothing plugged in wait behind More, which fades out as they rise in and the list slides up to
  make room. OK switches the TV to the input through its own player (the passthrough channel of an
  HDMI port); Back closes the menu. On the home screen the shade is dark frosted glass, a real blur:
  a 240-pixel copy of the screen blurred and darkened on the CPU (the TV can't blur live), the
  corner's shape baked into its transparency. Over other apps it is a plain shade: the menu is a
  window of Glass's accessibility service, which takes no focus and pauses nothing, so an HDMI
  picture keeps playing under it, and an HDMI picture is on a plane of the display hardware that
  Android can't read back, let alone blur. The window stays composed and laid out while hidden, so
  it comes up at once. The service (InputsKeyService) takes only the Input button from the apps,
  and the arrows, OK and Back while the menu is open; it is turned on in Settings → Accessibility →
  "Glass: Input button". Without it, or where Android lists no inputs, the Input button opens the
  TV's own panel. A debug build started with `--ez demo true` shows made-up inputs, and
  `tools/remote_keys.py input` presses the Input button on the emulator (see "Testing the screen
  savers").
- **Focus motion**: icons lift and grow on a spring, tilt toward the direction you moved, catch a
  moving glare, nudge against edges, and tick softly with each move.
- **Context menu** (hold OK, or the Menu key): Move, App Info, Uninstall. Move makes the other
  icons wiggle while you place the app with the arrows.
- **Top-right controls** (press Up from the top row): screen saver, inputs (HDMI), launcher
  settings, Android settings.
- **Inputs menu**, from the remote's Input button over any app (an HDMI picture, YouTube, the home
  screen) or the TV button in the capsule, like the "+" menu of Messages in iOS 17: round icons
  with big labels in the bottom-left corner (the device on an HDMI port by its own name over
  HDMI-CEC, "PlayStation 5", with the port beside it), on frosted white glass that covers only that
  corner and fades out toward the middle. As in Messages, the list comes in all at once, growing
  from 86% out of its left side as it fades in (~200 ms), and goes the same way; the inputs with
  nothing plugged in wait behind More, which slides the list up to make room for them. OK switches
  the TV to the input through its own player (the passthrough channel of an HDMI port); Back
  closes the menu. On the home screen the frost is a real blur: a 240-pixel copy of the screen
  blurred on the CPU (the TV can't blur live), its corner shape baked into its transparency. Over
  other apps it is a white fog: the menu is a window of Glass's accessibility service, which takes
  no focus and pauses nothing, so an HDMI picture keeps playing under it, and an HDMI picture is
  on a plane of the display hardware that Android can't read back, let alone blur. The service
  (InputsKeyService) takes only the Input button from the apps, and the arrows, OK and Back while
  the menu is open; it is turned on in Settings → Accessibility → "Glass: Input button". Without
  it, or where Android lists no inputs, the Input button opens the TV's own panel. A debug build
  started with `--ez demo true` shows made-up inputs, and `tools/remote_keys.py input` presses
  the Input button on the emulator (see "Testing the screen savers").
- **Focus motion**: icons lift and grow on a spring, tilt toward the direction you moved, catch a
  moving glare, nudge against edges, and tick softly with each move.
- **Context menu** (hold OK, or the Menu key): Move, App Info, Uninstall. Move makes the other
  icons wiggle while you place the app with the arrows.
- **Top-right controls** (press Up from the top row): screen saver, inputs (HDMI), launcher
  settings, Android settings.
- **Inputs menu** (the TV button in the capsule), like the "+" menu of Messages in iOS 17/18: the
  home screen turns into frosted white glass and the inputs fly out of the button, one after
  another, into a column of round icons with big labels (the device on an HDMI port by its own
  name over HDMI-CEC, "PlayStation 5", with the port beside it), settling with a little bounce. The
  inputs with nothing plugged in wait behind More, which unfolds them. OK switches the TV to the
  input through its own player (the passthrough channel of an HDMI port); Back folds the menu
  back into the button. The frost is a 240-pixel copy of the home screen blurred on the CPU (the
  TV can't blur live), and the home screen isn't drawn while it's covered. Where Android lists no
  inputs, the button opens the TV's own inputs panel. A debug build started with `--ez demo true`
  shows made-up inputs, for the emulator.
- **Screen savers with the tvOS 26 clock** (big rounded numerals in milky glass, at three quarters
  of the tvOS size, across the middle or stacked at a side, moving between shots; or, as a setting,
  a small clock with the date in a top corner, left and right in turn with each shot, or no clock). The numerals are drawn from their outlines with overlapping
  contours merged, so the rim never traces the joins inside a digit (SF Pro Rounded's 2):
  Apple Aerial (114 videos from Apple's CDN with location captions and the fade through black;
  recent ones cached on disk, up to 768 MB, so they start instantly), Landscapes (full-screen
  photos with a slow push-in), Portraits (the same full-screen photos and clock, side by side in a
  row that glides on to the next one every minute, the clock moving every three), Dynamic Wallpaper
  (Apple's Mac desktop pictures that follow the sun, see below), Live Wallpaper
  (the wallpaper's waves on the GPU) and StandBy (huge clock, red at night). Back on the top row
  starts the screen saver, as on Apple TV. Left/Right move to the next or previous shot or photo
  (Aerial, Landscapes, Portraits) and never close it; any other button does. An aerial that fails
  to load (a network hiccup) is retried with a growing pause before the saver falls back to stills.
  The next aerial's first 8 s load in the background and playback starts once 1.2 s is in, so Right
  cuts to the next shot at once; a cut shows black (or the shot's still) until the new video has a
  picture, never the old one frozen. When a video ends by itself, the decoder carries straight on
  into the next one, whose first picture arrives just before the cut is reported: the saver tells
  which shot a picture belongs to (ExoPlayer's analytics event), so it doesn't wait for one that
  has already come (it used to, and stayed on the second shot's still, or on black, for good). As a
  last guard, a second of video frames reaching the screen always clears the still or the black.
  Until the first video arrives, an aerial still holds the screen instead of black. Landscapes and
  Portraits decode the photos either side of the one on screen ahead. In Landscapes a photo
  dissolves in only once it is decoded, with the big clock in the same dissolve: two layers at
  most, however fast Left/Right are pressed. In Portraits the clock dissolves to its next place as
  the row glides, with every third photo.
- **Dynamic Wallpaper**: the nine dynamic desktop pictures of macOS that change with the time of day
  (Big Sur, Monterey Graphic, Ventura Graphic, Catalina, The Beach, The Cliffs, The Lake, The Desert,
  Solar Gradients; 77 frames), each showing the frame for where the sun is now, worked out as macOS
  does: NOAA's solar position for Skopje, and of the sun positions in the picture's own metadata
  (`apple_desktop:solar`) the one nearest it in the sky. When the sun moves on, the next frame
  dissolves in over 4 s. The same screen as the other savers: the clock moves with each new frame or
  picture, and the picture's name and the part of the day (Ноќ, Зора, Изгрев, Утро, Пладне, Попладне,
  Зајдисонце, Самрак) show in the bottom corner for 12 s after each change. Settings → Dynamic
  Wallpaper picks one, or all of them in turn (the default: the next picture every five minutes);
  Left/Right step through them. A frame settles in from 104.5% over 30 s, then nothing on screen
  moves until the next change, so the GPU rests. The pictures are Apple's and stay out of the app
  (see "Dynamic wallpapers" below).
- **Screen saver picker**: in Settings, Choose Screen Saver (or OK on the Screen saver row) opens
  the current saver with a caption; Left/Right step through all six, OK chooses the one on screen
  (the caption confirms it with a check mark and the picker returns to Settings, now showing it),
  Back returns to Settings unchanged.
- **Settings**: home style (tvOS 26 or big clock), wallpaper (Apple Aerial or 6 glass-wave wallpapers), clock style, screen saver and timeout (1 min–30 min or never), its clock (large, small or off),
  1080p/4K aerials, the Dynamic Wallpaper's picture (once the pictures are on the TV), sounds, Macedonian/English. Only TV (Leanback) apps are listed, and none are hidden.

## iPhone app

`ios/GlassRemote.xcodeproj` is Glass's remote for the iPhone (SwiftUI, iOS 26, "Glass" on the home
screen with the TV's icon), in Liquid Glass throughout: tiles, rows, buttons, menus, sheets and the
tab bar, every button answering the finger as iOS 26's glass does (it swells, lights up and springs
back), with a progressive blur where content scrolls under the title. Behind it (Settings, App
Background) is one of Glass's six wave wallpapers, the TV's own one, Pure Black for OLED, one of the
Gradients (soft colour fields in the spirit of Apple Home's wallpapers, drawn by the app as mesh
gradients: Apple's own are Apple's images and aren't copied in), or pictures of the household's own
from the iPhone (a long press removes one). The tab bar stays put while scrolling, so nothing moves
under the finger. The accent (what is on and chosen: tabs, switches, sliders, chosen rows) is iOS's
blue unless another is chosen in Settings. The Controls tab is titled with the TV's name, which Glass
keeps (`Prefs.tvName`, blank for the system's own) and announces over Bonjour; Settings renames it.
Its tabs are the control center and quick settings (Power Off, Restart, the sleep timer, the volume
and TCL's sound mode, the
picture preset and backlight, the light behind the TV with its colours as glass drops and any other
colour on the colour wheel, which becomes the tile's last colour, "Custom", on the TV too, and can be
made the Default; the TV's performance), the inputs (OK switches, a long press renames) and the apps
(opening them, and the last one), the screen savers (choosing one, starting it, and while it is on the
previous or next shot or stop, the Start button's glass flowing into those three; the Dynamic
Wallpaper's picture as a row of rounded squares, each picture as it looks at this hour, the frames
brought from the TV once, about 14 MB, into the app's caches) and the launcher's
settings, with the TV's own screens to open on it. The app speaks the TV's language. It follows
Apple's design principles: Power Off and Restart, rare and asked about first, are a low row so the
volume, picture and light fit the first screen; a choice (sound mode, preset, sleep timer, the light's
switch, settings) shows at once while the TV takes it, and the TV's answer replaces it or puts back what
was; buttons settle back without a bounce a tap has no momentum for; choices tick as iOS's pickers do,
actions tap; and with Reduce Motion buttons only light up or dim, and a wrong code doesn't shake. Its icon is an
Icon Composer document (`ios/GlassRemote/AppIcon.icon`, SVG layers): a glass TV showing Glass's waves
and a glass remote in front of it, on a blue-to-violet gradient, which iOS 26 renders as Liquid Glass in
its light, dark, tinted and clear looks (`ictool` in Icon Composer previews them).

Glass serves it on the home network (`remote/`): HTTP on port 7676, advertised over Bonjour as
`_glass._tcp` with the TV's id, so the phone finds the TV by itself, and again after the router gives
it another address (an address can also be typed in). Pairing is as Apple TV's Remote app: the phone
asks, the TV shows a four-digit code over whatever is on screen (a window of the accessibility
service, a toast without it), and the code typed on the phone gets a token, which the phone keeps in
its keychain and the TV as a hash; two minutes or five wrong codes end a code. While it is open the
phone follows the TV's state as server-sent events, so what the remote changes (its volume buttons
too) shows on the phone at once, and the TV shows what the phone changes at once, as it is the same
Compose state the TV's screens read. At rest the server costs the TV a thread waiting for a
connection, and all of it runs at background priority. When the TV has started Glass again without its
home screen (after an update, or memory taken back for an app), the apps are listed as a phone
connects, and the accessibility service notes the app in front as it connects; only activities count
as an app coming to the front, not TCL's panels over the picture, dialogs or the keyboard.

Restarting is only the system's to do, so Glass presses the button as the remote would
(`remote/TvButtons.kt`): its accessibility service opens the power menu (as holding Power does) and
clicks Restart. For that the service may read the windows on screen (`canRetrieveWindowContent`, with
the views marked unimportant, as TCL marks its pop-ups' labels), which it does only when the phone
asks. The emulator's power menu is empty, so Restart is only tried on the TV. The sound mode is TCL's:
its audio service wants MODIFY_AUDIO_ROUTING, and its pop-up takes a mode only from the remote's
arrows (neither a click nor the focus the service gives a row sets it). So Glass asks TCL's settings
app as TCL's voice assistant does (`remote/TvSound.kt`, read out of TvSettings): ShowWindowService
with `Type=Settings`, `ITEM=sound_preset`, `MOTION=SET` and `MOTION_VALUE` the mode's name as the TV
shows it (English here), which sets the mode and shows TCL's panel for it; once the setting has the
new mode, `com.tcl.quickmenu.dismiss` closes the panel. About a second in all on the TV (2026-10-31).
What else only TCL may set stays on the TV: the speakers, HDR presets, Game Master and HDMI-CEC open
TCL's own screens there (Energy not even that).

Power follows the TV: the state says whether its screen is on (`power.on`, from SCREEN_ON/OFF), and
the phone's tile is Turn Off while it is on and Turn On while it sleeps, the rest of the controls
dimmed. Asleep, the TV keeps Glass and the network up (TCL's network standby), so Turn On reaches it:
Glass starts `remote/WakeActivity.kt`, a transparent window with `turnScreenOn` in the manifest, in a
task of its own, which wakes the TV (`WAKE_REASON_APPLICATION`) and goes, leaving the input or app that
was on. A wake lock asking for a wake-up does nothing on TCL. Once awake, Glass reads the volume and
the picture again (it skips them asleep, when TCL reports its standby's muted sound and another
preset), and the sound mode is followed as it changes (a ContentObserver on
`tcl_sys_audio_sound_mode`), so the remote's or TCL's own menus' changes show on the phone too. Under
the light, three buttons open Glass's control center, inputs menu and quick settings on the TV, as
their remote buttons do (`POST /v1/open` with `controlCenter`, `inputs`, `quickSettings`).

Two widgets of quick controls for the Home Screen (Брзи контроли): round buttons with icons only, the
words kept for VoiceOver. Small: power, mute, the screen saver and the light, under the TV's name and
its volume. Medium: power and mute beside the volume (a capsule with its level between round − and +),
then the screen saver, the light, Glass's inputs menu and the last app. A button is lit in the app's
accent (the light in its own colour) while what it stands for is on. They are light on the battery: a
widget never asks the TV anything by itself; it shows the TV as the app last heard it, written to the
app group as the state comes and drawn again 1.5 s after something it shows has changed, and a button
sends one request only when pressed (volume 5% a press on the TV; power, mute, the light and the
screen saver are toggles on the TV, which knows what is on), the TV's answer becoming what it shows.
Once an hour WidgetKit asks it again and it looks at which network the phone is on (no traffic).
The token is in the app group's keychain group, so the widgets reach the TV as the app does.

Off the TV's Wi-Fi, the app and the widgets say so: no Wi-Fi at all ("Поврзи се на Wi-Fi"), or
another network (the phone's address isn't in the TV's private network), told apart from the phone's
own interfaces without sending anything. The app shows a card in place of the controls (or a banner
over them, which nudges when a control is tried), stops retrying off any Wi-Fi and tries every 30 s on
another one, and follows the TV again the moment the phone is back on its network; the widgets dim
their buttons under the same words. A debug build started with `-glassReach offWifi` (or
`otherNetwork`) pretends, for the simulator.

The API, JSON both ways, every POST answering with the state after it: `GET /v1/info`, `POST /v1/pair`,
`/v1/pair/confirm {code, device}`, `/v1/pair/cancel`; then with `Authorization: Bearer <token>`:
`GET /v1/state`, `/v1/events`, `/v1/stats`, `/v1/apps/art?key=`, `/v1/dynamic` (the dynamic pictures'
`dynamic.json` with the place), `/v1/dynamic/frame?id=&frame=` (a frame's 1200x1200 square);
`POST /v1/settings {key, value}`,
`/v1/volume {percent | step (that many 1% steps, up to 20) | mute | toggleMute}`, `/v1/picture {mode | backlight}`, `/v1/sleep {minutes}`, `/v1/name {name}`,
`/v1/light {on | toggle | color | rgb | default}`, `/v1/power/off`, `/v1/power/on`, `/v1/power/toggle`, `/v1/power/restart`, `/v1/sound {mode}`, `/v1/inputs/watch {id}`,
`/v1/inputs/rename {id, name}`, `/v1/apps/launch {key}`, `/v1/apps/last`,
`/v1/saver {action: start | next | previous | stop | toggle}`, `/v1/open {screen}`, `/v1/unpair`.
On the emulator `adb forward tcp:7676 tcp:7676` makes it 127.0.0.1:7676 on the Mac, and
`dns-sd -P "Android TV емулатор" _glass._tcp local 7676 glass-emulator.local 127.0.0.1 id=<the TV's id from /v1/info>`
announces it over Bonjour, so the app in the iOS Simulator finds it by itself. The app keeps its
token in the keychain, which a build without a signature can't use (it pairs, then forgets the TV at
once): build for the simulator as Xcode does, signed to run locally.

```bash
xcodebuild -project ios/GlassRemote.xcodeproj -scheme GlassRemote -destination 'generic/platform=iOS Simulator' build
```

`tools/make_ipa.sh` builds it for iPhone hardware as `ios/build/Glass.ipa`, signed ad hoc, to be
signed with one's own certificate when installing it. The app and its widgets
(`com.andrej.glassremote.widgets`) share the app group `group.com.andrej.glassremote`, which the ad hoc
signature carries for the signing tool to keep; without it the app works and the widgets ask to open
it. A tool that renames the group (AltStore) notes it in `ALTAppGroups`, which both read.

## Testing the screen savers

On the TV or the emulator, Settings → Choose Screen Saver, pick one with Left/Right and OK, then
start it with the screen saver button (a framed landscape) in the top-right capsule (or Back on the top row).
To see it start by itself, set Start after to 1 min and leave the remote alone on the home screen.

Debug builds also start them straight from adb, optionally in a fast mode where photos change every
4 s (Landscapes and Portraits) and aerials every 20 s, the clock moving with each. A fast aerial
jumps to the last seconds of its video, so each cut is the one the saver makes by itself when a
video ends (Left/Right try the other kind, a seek):

```
adb shell am start -n com.andrej.glasstv/.screensaver.ScreensaverActivity --es saver landscapes --ez fast true
```

`saver` is one of `aerial`, `landscapes`, `portraits`, `dynamic`, `liquid`, `standby`. A fast
Dynamic Wallpaper is a day in 96 seconds (an hour every 4 s), with the next picture every 8 s. As the system screen saver
(over other apps), select `GlassDreamService` as described in its KDoc and start it with
`adb shell am start -n com.android.systemui/.Somnambulator`.

`adb shell input keyevent` doesn't go through accessibility key filters, and the emulator has no
Input button, so `tools/remote_keys.py` stands in for a Google TV remote (a virtual HID device made
with Android's `hid` tool) whose buttons go through the real input pipeline:

```
tools/remote_keys.py -s emulator-5554 input wait:1000 down down ok
tools/remote_keys.py -s emulator-5554 settings wait:800 right
```

## Performance notes

The TV has a small PowerVR GPU and no RuntimeShader (Android 12), so:

- glass samples small pre-blurred copies of the wallpaper instead of blurring live;
- the clock is rendered once per minute into its own layer;
- nothing animates while the screen is idle (0 frames drawn), and Compose's frame clock pauses
  when another app is in front;
- every full-screen layer that can't be seen is skipped: the wallpaper is drawn once, the grid's
  grey is baked into its blurred copy, and only the lifted icon casts a shadow;
- the dock's refracting glass is worked out once per backdrop on a background thread (~20 ms on
  the emulator: the body at half resolution, per-pixel work only along the curved rim) and drawn
  as a single bitmap;
- tile art is decoded at exactly the size a focused tile has on the screen, so it is never scaled
  up and barely scaled down;
- the APK carries a baseline profile for all of the launcher's code, and the install step below
  compiles it ahead of time, so the first moves are as smooth as later ones;
- animations run at their designed speed even though the TV sets animator scale to 0.5;
- the Live Wallpaper shader renders at 960x540 / 30 fps and is upscaled by the display hardware;
- a tilting icon draws its rounded rects into a layer of their own, and the tilt turns that
  texture: under perspective the GPU can't draw a rounded rect analytically, and Skia rasterized
  each one on the CPU and uploaded it, every frame (over a thousand uploads in nine seconds of
  moving along the dock);
- the darkening behind the dock is baked into each backdrop instead of drawn over it, the Top
  Shelf dissolve uses two full-screen layers (three only for a moment), and it starts once the
  focus has rested 110 ms and takes 380 ms; icons scrolled off the screen aren't drawn;
- the dock's six shelves are built ahead (the focused one first) and kept in memory, and each
  shelf's dock glass, the costliest part, is kept on disk, so after the TV closes the launcher to
  free memory for an app the shelves come back without rendering it again;
- the live aerial behind the big clock plays only while the remote rests: a key fades the shot's
  still in over it and pauses it, and it returns 2.5 s later; coming back to the home screen it
  carries on where it was, from the disk cache;
- aerials use 10-bit HEVC only where a hardware decoder supports Main10 (the TV's Realtek one
  does), otherwise Apple's 8-bit H.264 versions;
- the aerials keep one video decoder from shot to shot: it is set up with room for any aerial of its
  size (`RoomyVideoRenderer`). ExoPlayer used to replace it at every cut, and the TV's Realtek
  decoder fails (0x80001000) when a new 4K instance starts while the last is still going away, and
  then keeps failing: the saver went black, or stopped on a still with its caption. `DecoderGate`
  keeps any replacement (a new saver, the live wallpaper, the system screen saver) from overlapping
  the one it replaces; each player gives its decoder up as soon as its screen is paused; a decoder
  hiccup is retried on the same video; the aerial order (which reads the disk cache index) is worked
  out off the UI thread, and the cache is opened when the app starts;
- fades never use an offscreen buffer (`CompositingStrategy.ModulateAlpha`, and colour fades for the
  scrims): AnimatedVisibility's fadeIn uses one, and on the TV's tile-based GPU every offscreen
  buffer flushes and reloads the whole screen. The settings sheet and the context menu hold their
  glass in a layer that stays as it is while the list moves (sampled where the sheet comes to rest,
  so the slide in doesn't redraw it either): the sheet went from 31% late frames to 3%;
- tile art is drawn on all cores at once, so after a restart the dock is complete in about a second;
- the home screen reads the focus through derived state, and each icon reads its own share of it
  (`HomeTile`): a move recomposes the icon the focus leaves and the one it reaches, not the screen
  (~5 ms of the TV's main thread on every press before);
- the backdrop is a plain (not static) composition local, and the glass, the dock and the capsule
  follow the dissolve in the draw phase: the first frame of every Top Shelf dissolve recomposed the
  whole home screen (16–28 ms);
- the navigation sounds play on a thread of their own: SoundPool waits for the audio server, ~70 ms
  on the first press after a pause, and the focus waited with it;
- every blurred copy of a backdrop is uploaded to the GPU when it is made, off the main thread
  (hardware bitmaps); the render thread used to upload them in a dissolve's first frames;
- the semantics tree is empty unless a screen reader is on: any accessibility service (Glass's own
  among them) made Compose walk the whole screen every 100 ms while anything moved;
- every string is shaped once in each weight at start, off the main thread, and the control center and
  the inputs menu stay composed (hidden) once opened: opening the control center took a 118 ms frame,
  most of it measuring text in SF Pro read from disk;
- Compose's layers never clip to their bounds, so HWUI counts any change in one as a change of the whole
  window and redraws it all. A grid icon's focus animation only changes layer properties (its shadow,
  glare and rim are layers of their own while it is lit), and each icon and label draws through a
  RenderNode that clips (`damageBounds`): a move along a row of the grid redraws the screen around the
  two icons, about a fifth of it (`dumpsys SurfaceFlinger`, SurfaceDamageRegion);
- nothing under a layer that moves or scales may read where it is while it is placed (`onPlaced`
  with `positionInParent()`, `onGloballyPositioned`): Compose 1.12 then lays it out again on every
  frame of the animation, and a layout redraws the whole window, `damageBounds` or not. The quick
  settings' tabs read their places that way, for their lists to rise from, and every move redrew the
  whole quick settings; their places are now worked out from the tabs' widths. Over an HDMI picture
  a walk through every row went from ~16% late frames (median 29 ms, GPU 13 ms) to ~10% (15 ms, GPU
  5 ms). To check a window: `atrace -a com.andrej.glasstv view` shows `AndroidOwner:draw` on every
  frame when Compose redraws all of it, and `setprop debug.hwui.show_dirty_regions true` (then
  `service call activity 1599295570`) flashes what HWUI redraws, which `screencap` catches over the
  home screen (not over an HDMI picture, which is secure);
- an icon the focus leaves settles back flat (its tilt and nudge used to freeze where the next press
  found them), the lens finishes its glide when the focus leaves the dock, and a Top Shelf dissolve
  that the next one interrupts finishes in a moment instead of jumping to full.

## Dynamic wallpapers

The Dynamic Wallpaper saver's pictures are Apple's macOS desktop pictures, which Apple licenses
with macOS, so the app doesn't include them: `tools/push_dynamic.sh <adb serial>` downloads them from
Apple's own servers as macOS does when one is chosen (the addresses from this Mac's catalog of desktop
pictures, `com_apple_MobileAsset_DesktopPicture.xml`, each checked against its SHA-1; ~780 MB, once,
into `~/Library/Caches/GlassTV/dynamic`), splits each HEIC into its frames
(`tools/dynamic_frames.swift`: 1920x1080 for the TV, 16:9 out of Apple's 6016x6016 squares, sRGB; and
the whole square at 1200x1200 in Display P3 for the iPhone app) with `dynamic.json` listing the sun's
position for each, and copies them into `Android/data/com.andrej.glasstv/files/dynamic/` (42 MB).
Without them the saver isn't in the settings' list of pictures and falls back to the Live Wallpaper.

## Font

All text is set in Apple's SF Pro, and the screen saver clock in SF Pro Rounded (the tvOS 26 clock
face), when they are on the TV: `tools/push_fonts.sh <adb serial>` copies `SFNS.ttf` and
`SFNSRounded.ttf` from the Mac into `Android/data/com.andrej.glasstv/files/fonts/`, and the
launcher picks them up when it starts. Apple licenses its fonts for Apple platforms only, so the
app doesn't include them; without them Inter / Inter Display and Nunito (with its plain "1"), all
SIL Open Font License, stand in. A font at `fonts/clock.ttf` (or `.otf`) overrides the clock face.

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. A debug build started with
`--ez demo true` adds placeholder tiles for previewing the grid on an emulator.

Assets are generated by the scripts in `tools/` (wallpapers and banner, navigation sounds, the
aerial list from Apple's `resources-16.tar` manifest, and `grab_frames.py` for picking new
wallpaper shots). `pull_banners.py` copies just the banners of the TV's apps (via `unzip -p` on
the TV), `topshelf_preview.py` renders their Top Shelves on the Mac, and `make_stubs.py` builds
code-less stand-ins with those banners so the arm64 emulator can show the TV's real app row.
The banners are kept in `design/banners`. `make_tiles.py <chrome-headless-shell>` draws the tiles
and the vector-based logos in `design/logos/` for the design canvas, `extract_logos.py` lifts the
remaining logos out of the banners, and `export_topshelf.py <chrome-headless-shell>` renders the
canvas boards into the app's assets. Logos without a vector source go through `logokit.py`: edge
pixels are unmixed into background and logo instead of being rounded to the nearest colour (which
left white fringes), and edges are redrawn from a signed distance field, so they stay straight and
crisp at any size.

`preview/demo.mp4` is a recording from the emulator: moving along the dock, then the screen saver
preview stepping through all five savers.

## Install (when approved)

```bash
adb -s 192.168.100.125:5555 install -r app/build/outputs/apk/release/app-release.apk
# compile all of it ahead of time so it is smooth from the first second
adb -s 192.168.100.125:5555 shell cmd package compile -m speed -f com.andrej.glasstv
adb -s 192.168.100.125:5555 shell cmd package set-home-activity com.andrej.glasstv/.HomeActivity
# Apple's fonts for all the text (see Font)
tools/push_fonts.sh 192.168.100.125:5555
# Apple's dynamic desktop pictures for the Dynamic Wallpaper saver (see Dynamic wallpapers)
tools/push_dynamic.sh 192.168.100.125:5555
# Glass as the system screen saver everywhere, not only on the home screen. TCL's TclAppBoot
# refuses to bind the screen saver service of an app without its auto-start permission, so the
# system would silently keep showing nothing: grant it first.
adb -s 192.168.100.125:5555 shell appops set com.andrej.glasstv APP_AUTO_START allow
adb -s 192.168.100.125:5555 shell settings put secure screensaver_components com.andrej.glasstv/.screensaver.GlassDreamService
adb -s 192.168.100.125:5555 shell settings put secure screensaver_default_component com.andrej.glasstv/.screensaver.GlassDreamService
# The control center's Wi-Fi tile: Android names the network only to apps allowed the location.
adb -s 192.168.100.125:5555 shell pm grant com.andrej.glasstv android.permission.ACCESS_FINE_LOCATION
# Its Ambient Light tiles: the LED strip is reached over Bluetooth.
adb -s 192.168.100.125:5555 shell pm grant com.andrej.glasstv android.permission.BLUETOOTH_CONNECT
```

### On another TV

- `192.168.100.125:5555` is this TV. On another one turn on developer options and network
  debugging, `adb connect <its ip>:5555`, allow the prompt on the TV, and run the commands above
  with its address.
- On a fresh clone Android Studio writes `local.properties` (the SDK path) when it opens the project.
- The release APK is signed with the building computer's debug key (`~/.android/debug.keystore`).
  An APK built on another computer can't update an install made from this one: uninstall first,
  or copy that keystore across.
- `push_fonts.sh` and `push_dynamic.sh` need a Mac: they take Apple's fonts and desktop pictures
  from macOS. Without them Glass draws its text in Inter and Nunito, and the Dynamic Wallpaper
  saver has no pictures.
- The Ambient Light tiles look for this TV's LED strip (`Prefs.AMBIENT_STRIP`).

The remote's Input button opens Glass's inputs menu, and Home held the control center, once its
accessibility service is on: on the TV, Settings → Accessibility → "Glass: Input button" (it stays
on through updates).

CouchyTV has been uninstalled from the TV and Google TV's own launcher is disabled, so Glass is
the only home screen (Android's FallbackHome aside). To go back to Google TV's:
`adb shell pm enable com.google.android.apps.tv.launcherx`, and to the Google screen saver:
`adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop`.

### On the TV

- The panel is 4K, but the system draws everything at 1920x1080 (`wm size` override) and its
  display hardware won't scale an app's layer, so SurfaceFlinger composites the launcher on the GPU
  every frame. Video goes to its own hardware plane: `screencap` can't see it and shows black where
  an aerial is playing (the decoder's frame counter in logcat, `MediaCodec ... ROB:30/s`, shows it is).
- The inputs menu, measured on the TV back to back with the dock (2026-10-25, the TV in its slow
  phase): moving through the open menu 20 ms GPU a frame, 16% late frames, against 22 ms and 20%
  for moving along the dock. Leaving out the frost or the held home screen changed nothing then:
  ~19 ms was the TV's floor for any frame at that time.
- Measured on the TV (dock: five moves right and back, 450 ms apart): 0.8% late frames, GPU
  10 ms a frame, 0 frames drawn at rest; the app grid 4%, GPU 16 ms; the settings sheet 3%; the
  capsule 2%; the context menu 11%; the screen savers 1%, GPU 5 ms, with a picture 90–230 ms after
  each cut; back home from an app in ~100 ms; a cold start complete in ~1 s; ~70–120 MB of memory. The same build has also measured 20% late frames with GPU at 22 ms
  for a while, with nothing else drawing (the TV's own state), so compare runs back to back.
- The Dynamic Wallpaper, measured on the TV on 2026-11-02 (all in turn): while a frame settles in
  (30 s) 0.06% late frames, GPU 4 ms a frame; then at rest one frame a minute (the clock) and 0.05 s
  of CPU a minute; the change to the next picture 0.06% late frames, GPU 4 ms (99th percentile 7 ms);
  ~96 MB of memory while it runs.
- Measured on the TV on 2026-10-27, before and after the main-thread and redraw changes in the
  performance notes, with the GPU in its cool phase (SurfaceFlinger's own GPU wait ~1.8 ms a frame;
  after 15–20 minutes of animating without a break the TV heats up and every frame of every build
  takes longer, so let it rest before comparing): moving along the dock 2.4% → 0.3% late frames; a
  press to the end of its first frame 27 ms on average and 85 ms at worst → 13 ms and 17 ms; the
  longest main-thread frame 37 ms → under 16 ms; the control center's opening frame 118 ms → 30 ms;
  the grid's median GPU frame 17 ms → 10 ms (scrolling between rows and into the grid still redraws
  the whole screen and stays GPU-bound, ~24 ms).
- The TV's logcat ring buffer is only 64 KB and rolls over in seconds: stream it
  (`adb logcat -v time -s TopShelf:*`) rather than reading it afterwards.
