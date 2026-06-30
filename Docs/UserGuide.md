# User Guide

What you can do in **dlm**, a download manager for Android. The app has three
main tabs along the bottom — **Downloads**, **Review**, **Settings** — plus two
sub-screens reached from within them (**archive.org sign-in** and **Activity**).
The **Review** tab shows a badge with the number of links waiting.

## Adding links

You can start a download four ways:

- **Tap "Add"** (on Downloads or Review) to open the *Add a link* dialog.
- **Paste a link** into the dialog, or tap **"Paste from clipboard"**. If the
  clipboard already holds a link when you open the dialog, it's filled in for you.
- **Share a link from another app** (Share → dlm), or open an `http(s)` link with
  dlm — the Add dialog opens pre-filled.
- **Copy a link with clipboard-watching on** — it's added to Review automatically
  while the app is open (no dialog). See Settings.

In the Add dialog you choose:

- **"Add"** → sends the link to **Review** first, so you can check it before
  downloading.
- **"Download now"** → starts it right away.

Both buttons are enabled only once the text is a valid, safe link.

## Review tab — check links before downloading

Links the app extracts from a page land here first so you can look them over.

- **Change the grouping** — *Site + packages*, *Site*, or *Packages*.
- **Collapse/expand** a site group or a package (folder).
- **Confirm all links from one site** — the ✓✓ button on a site header.
- **Start all downloads** at once, or **Clear** the whole review list (bottom bar).
- **Toggle clipboard-watching** from the toolbar (the paste icon).
- **Tap a single link** for its menu: **Start download**, **Add to downloads
  (don't start)**, **Skip / Include this link**, or **Remove**.
- **Tap a package** for its menu: **Start all in this folder**, **Add to downloads
  (don't start)**, set **Importance** (priority), **Reorder**, or **Remove folder**.

## Downloads tab — manage the queue

Each download shows its file-type icon, the source-site favicon, and live
**progress %, size, speed, and ETA**. Small flag icons mark items that are
skipped, set to start manually, starting now, or unavailable. Grouped downloads
appear as collapsible folders with a "*N downloading*" count, and an
**active-downloads summary bar** (count + combined speed) sits at the top.

Toolbar actions:

- **Activity** button (spins while work is running) → opens the Activity screen.
- **Pause all / Resume all** (toggles "download automatically").
- **Clear finished** (appears once something has finished).

Tap any download for its menu:

- **Pause** or **Resume** (depending on its state)
- **Start now** — jump the queue
- **Save to folder…** — export a finished file to your chosen folder
- **Start automatically / Start manually**
- **Skip / Include this download**
- **Importance** — priority from Lowest to Highest
- **Reorder** — move to top, up, down, or to bottom
- **Remove** — deletes the item and its partial file (asks to confirm)

Tap a package header for: set **Importance**, **Reorder**, or **Remove folder**
(asks to confirm).

## Settings tab

- **Downloads** — set **how many download at once** (slider, 1–8); set a **speed
  limit** (e.g. `2M` or `500k`, blank for no limit); toggle **"Download
  automatically"** (when off, downloads wait until you start them).
- **Adding links** — toggle **"Watch clipboard for links"** (works only while the
  app is open; Android blocks clipboard access in the background).
- **Where to save** — **Choose / Change folder**. Finished downloads are saved
  there automatically; until you pick one they stay inside the app.
- **Video & audio sites** — support for YouTube and many other sites via yt-dlp.
  Shows its status; sets up automatically on first use, with a **"Set up now"**
  button to prepare or retry it.
- **Activity** — **"View activity"** opens the Activity screen.
- **Internet Archive** — **"Sign in (optional)" / "Signed in — manage"** opens the
  archive.org screen.
- Shows the installed **app version** at the bottom.

## archive.org sign-in (optional)

Signing in is optional — most downloads work without it. It only matters for
restricted items on archive.org.

- **Sign in with email + password** (the simple path).
- **Advanced options** — save **API keys** (S3 access + secret, from
  archive.org/account/s3.php), or paste a **login cookie**.
- **Sign out.**
- The screen shows your current sign-in status and any error message.

## Activity screen

A live list of background tasks (video-support setup, yt-dlp updates, queue load),
each with a spinner/progress, a success ✓, or an error icon plus detail text.
**Clear** removes finished entries.

## What happens automatically

- A **foreground service with a progress notification** keeps downloads running in
  the background.
- Interrupted downloads **resume** where they left off.
- **yt-dlp updates itself** in the background once it's set up.
- The app asks for **notification permission** on first launch (for the progress
  notification).
