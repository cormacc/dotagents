#!/usr/bin/env node

import { spawn, execSync } from "node:child_process";
import { existsSync } from "node:fs";
import { platform, homedir } from "node:os";
import { join } from "node:path";
import puppeteer from "puppeteer-core";

const useProfile = process.argv[2] === "--profile";

if (process.argv[2] && process.argv[2] !== "--profile") {
	console.log("Usage: browser-start.js [--profile]");
	console.log("\nOptions:");
	console.log("  --profile  Copy your default Chrome/Chromium profile (cookies, logins)");
	process.exit(1);
}

const SCRAPING_DIR = join(homedir(), ".cache", "chromium-debug");

// Check if already running on :9222
try {
	const browser = await puppeteer.connect({
		browserURL: "http://localhost:9222",
		defaultViewport: null,
	});
	await browser.disconnect();
	console.log("✓ Chrome/Chromium already running on :9222");
	process.exit(0);
} catch {}

// ---------------------------------------------------------------------------
// Find Chrome/Chromium binary and default profile path per platform
// ---------------------------------------------------------------------------

function findBrowser() {
	const os = platform();
	const home = homedir();

	if (os === "darwin") {
		const candidates = [
			"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
			"/Applications/Chromium.app/Contents/MacOS/Chromium",
			"/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
		];
		const bin = candidates.find(existsSync);
		if (!bin) {
			console.error("✗ No Chrome or Chromium found in /Applications");
			process.exit(1);
		}
		const profileDir = join(home, "Library", "Application Support", "Google", "Chrome");
		return { bin, profileDir: existsSync(profileDir) ? profileDir : null };
	}

	if (os === "linux") {
		// Try common binary names via which
		const names = ["google-chrome-stable", "google-chrome", "chromium-browser", "chromium"];
		let bin = null;
		for (const name of names) {
			try {
				bin = execSync(`which ${name}`, { encoding: "utf-8" }).trim();
				if (bin) break;
			} catch {}
		}
		if (!bin) {
			// Try common paths
			const paths = [
				"/usr/bin/google-chrome-stable",
				"/usr/bin/google-chrome",
				"/usr/bin/chromium-browser",
				"/usr/bin/chromium",
				"/snap/bin/chromium",
			];
			bin = paths.find(existsSync) ?? null;
		}
		if (!bin) {
			console.error("✗ No Chrome or Chromium found on PATH or in /usr/bin");
			process.exit(1);
		}
		// Default profile locations on Linux
		const profileCandidates = [
			join(home, ".config", "google-chrome"),
			join(home, ".config", "chromium"),
			join(home, "snap", "chromium", "common", "chromium"),
		];
		const profileDir = profileCandidates.find(existsSync) ?? null;
		return { bin, profileDir };
	}

	if (os === "win32") {
		const localAppData = process.env.LOCALAPPDATA ?? join(home, "AppData", "Local");
		const programFiles = process.env["ProgramFiles"] ?? "C:\\Program Files";
		const programFilesX86 = process.env["ProgramFiles(x86)"] ?? "C:\\Program Files (x86)";
		const candidates = [
			join(localAppData, "Google", "Chrome", "Application", "chrome.exe"),
			join(programFiles, "Google", "Chrome", "Application", "chrome.exe"),
			join(programFilesX86, "Google", "Chrome", "Application", "chrome.exe"),
			join(localAppData, "Chromium", "Application", "chrome.exe"),
		];
		const bin = candidates.find(existsSync) ?? null;
		if (!bin) {
			console.error("✗ No Chrome or Chromium found in standard Windows locations");
			process.exit(1);
		}
		const profileDir = join(localAppData, "Google", "Chrome", "User Data");
		return { bin, profileDir: existsSync(profileDir) ? profileDir : null };
	}

	console.error(`✗ Unsupported platform: ${os}`);
	process.exit(1);
}

const { bin, profileDir } = findBrowser();

// Setup scraping directory
execSync(`mkdir -p "${SCRAPING_DIR}"`, { stdio: "ignore" });

// Remove lock files to allow new instance
try {
	for (const lock of ["SingletonLock", "SingletonSocket", "SingletonCookie"]) {
		const lockPath = join(SCRAPING_DIR, lock);
		if (existsSync(lockPath)) {
			execSync(`rm -f "${lockPath}"`, { stdio: "ignore" });
		}
	}
} catch {}

if (useProfile && profileDir) {
	console.log(`Syncing profile from ${profileDir}...`);
	try {
		execSync(
			`rsync -a --delete \
				--exclude='SingletonLock' \
				--exclude='SingletonSocket' \
				--exclude='SingletonCookie' \
				--exclude='*/Sessions/*' \
				--exclude='*/Current Session' \
				--exclude='*/Current Tabs' \
				--exclude='*/Last Session' \
				--exclude='*/Last Tabs' \
				"${profileDir}/" "${SCRAPING_DIR}/"`,
			{ stdio: "pipe" },
		);
	} catch (e) {
		console.warn(`⚠ Profile sync failed: ${e.message}`);
		console.warn("  Continuing without profile...");
	}
} else if (useProfile && !profileDir) {
	console.warn("⚠ No default profile directory found, starting without profile");
}

// Start browser
spawn(
	bin,
	[
		"--remote-debugging-port=9222",
		`--user-data-dir=${SCRAPING_DIR}`,
		"--no-first-run",
		"--no-default-browser-check",
	],
	{ detached: true, stdio: "ignore" },
).unref();

// Wait for browser to be ready
let connected = false;
for (let i = 0; i < 30; i++) {
	try {
		const browser = await puppeteer.connect({
			browserURL: "http://localhost:9222",
			defaultViewport: null,
		});
		await browser.disconnect();
		connected = true;
		break;
	} catch {
		await new Promise((r) => setTimeout(r, 500));
	}
}

if (!connected) {
	console.error("✗ Failed to connect to browser");
	process.exit(1);
}

console.log(`✓ Browser started on :9222${useProfile ? " with your profile" : ""}`);
console.log(`  ${bin}`);
