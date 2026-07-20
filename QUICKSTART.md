# OpenShark Mobile — Quick Start (No Thinking Required)

## What You Need

- Android phone with Termux (F-Droid)
- Your OpenShark repo cloned somewhere
- Android Studio OR `adb` for APK install

## Step 1: Copy This Folder

```bash
# On your dev machine, copy openshark-mobile/ into your OpenShark repo
cp -r openshark-mobile /path/to/your/openshark/
```

## Step 2: Run One Command

```bash
cd /path/to/your/openshark/openshark-mobile
./install.sh
```

This does everything:
- ✅ Patches your Cargo.toml
- ✅ Adds the `server` CLI command
- ✅ Installs the HTTP gateway module
- ✅ Creates default config
- ✅ Builds the Rust binary (if in Termux)

## Step 3: Setup Local LLM (on your phone, in Termux)

```bash
# In Termux:
cd openshark/openshark-mobile
bash scripts/complete-setup.sh
```

This downloads:
- ✅ Ollama (local LLM runtime)
- ✅ Gemma 4 E2B (2B params, ~7GB)

## Step 4: Start the Server

```bash
# In Termux:
openshark server --port 9876
```

Leave this running. Background it with `Ctrl+Z` then `bg` if needed.

## Step 5: Build & Install APK

```bash
# On your dev machine:
cd openshark/openshark-mobile/android
./build-apk.sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Step 6: Chat

Open the app. It connects to `http://127.0.0.1:9876` automatically. Start chatting.

**You are now offline. No data leaves your phone.**

---

## Troubleshooting

**"Cannot connect"** → Is `openshark server` running? Check with `curl http://127.0.0.1:9876/v1/health`

**"Model not found"** → Run `ollama pull gemma4:e2b` in Termux

**"Slow"** → Use smaller model (E2B vs E4B). Close other apps. Add swap.

**"Build fails"** → `pkg install rust cargo sqlite pkg-config` in Termux

---

## That's It

6 steps. One script does the heavy lifting. You're running a local AI coding assistant on your phone.

🎹🦞
