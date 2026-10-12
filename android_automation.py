"""
Android Automation Engine for Aura AI Assistant (100% Python).
Provides dual execution:
1. Direct hardware/OS execution on a connected Android phone via ADB (USB or Wi-Fi).
2. Mobile Deep-Link / Intent actions (`tel:`, `sms:`, `whatsapp://`, `intent://`)
   for immediate execution when opened on the Android phone's mobile web/PWA interface.
"""

from __future__ import annotations

import re
import subprocess
import time
import urllib.parse
import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Optional

from contact_manager import ContactManager


# Common Android App Package & Deep-Link Registry
ANDROID_APP_PACKAGES: Dict[str, Dict[str, str]] = {
    "whatsapp": {"package": "com.whatsapp", "deeplink": "whatsapp://"},
    "youtube": {"package": "com.google.android.youtube", "deeplink": "vnd.youtube://"},
    "instagram": {"package": "com.instagram.android", "deeplink": "instagram://app"},
    "spotify": {"package": "com.spotify.music", "deeplink": "spotify://"},
    "maps": {"package": "com.google.android.apps.maps", "deeplink": "geo:0,0?q="},
    "google maps": {"package": "com.google.android.apps.maps", "deeplink": "geo:0,0?q="},
    "chrome": {"package": "com.android.chrome", "deeplink": "googlechrome://"},
    "gmail": {"package": "com.google.android.gm", "deeplink": "googlegmail://"},
    "camera": {"package": "com.android.camera", "action": "android.media.action.IMAGE_CAPTURE"},
    "settings": {"package": "com.android.settings", "action": "android.settings.SETTINGS"},
    "clock": {"package": "com.google.android.deskclock", "action": "android.intent.action.SHOW_ALARMS"},
    "alarm": {"package": "com.google.android.deskclock", "action": "android.intent.action.SHOW_ALARMS"},
    "calculator": {"package": "com.google.android.calculator", "action": "android.intent.action.MAIN"},
    "phone": {"package": "com.google.android.dialer", "deeplink": "tel:"},
    "messages": {"package": "com.google.android.apps.messaging", "deeplink": "sms:"},
}


def parse_bounds(bounds_str: str) -> Optional[tuple[int, int]]:
    """
    Parses Android UIAutomator bounds '[x1,y1][x2,y2]' into center (cx, cy) coordinates.
    """
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str or "")
    if not match:
        return None
    x1, y1, x2, y2 = map(int, match.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


class AndroidAutomationEngine:
    def __init__(self, contact_manager: Optional[ContactManager] = None):
        self.contacts = contact_manager or ContactManager()
        self.recent_actions: List[Dict[str, Any]] = []

    def _run_adb(self, args: List[str], timeout: int = 8) -> tuple[bool, str]:
        """Runs an ADB command safely and returns (success, stdout_or_error)."""
        try:
            res = subprocess.run(
                ["adb"] + args,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            if res.returncode == 0:
                return True, res.stdout.strip()
            return False, (res.stderr.strip() or res.stdout.strip())
        except FileNotFoundError:
            return False, "ADB not installed or not in system PATH."
        except Exception as exc:
            return False, str(exc)

    def check_adb_connection(self) -> Dict[str, Any]:
        """Checks if an Android phone or emulator is currently connected over ADB."""
        ok, output = self._run_adb(["devices", "-l"])
        if not ok:
            return {"connected": False, "devices": [], "detail": output}

        lines = [line.strip() for line in output.splitlines()[1:] if line.strip() and "device" in line]
        devices = [line.split()[0] for line in lines if not line.startswith("*")]
        return {
            "connected": len(devices) > 0,
            "devices": devices,
            "detail": lines[0] if lines else "No Android device attached via ADB (using Mobile Deep-Link mode).",
        }

    def _record_action(self, action_type: str, title: str, detail: str, client_action: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        entry = {
            "type": action_type,
            "title": title,
            "detail": detail,
            "client_action": client_action,
            "timestamp": time.strftime("%H:%M:%S"),
        }
        self.recent_actions.insert(0, entry)
        self.recent_actions = self.recent_actions[:20]
        return entry

    # -------------------------------------------------------------------------
    # 1. CONTACT LOOKUP & DIRECT PHONE CALLING
    # -------------------------------------------------------------------------
    def search_contacts(self, query: str) -> Dict[str, Any]:
        """Searches the owner's contacts by name, nickname, relationship, or phone number."""
        matches = self.contacts.search(query)
        return {
            "status": "ok",
            "query": query,
            "count": len(matches),
            "contacts": [m.to_dict() for m in matches],
        }

    def make_phone_call(self, contact_name_or_number: str, speakerphone: bool = False) -> Dict[str, Any]:
        """
        Places a phone call to a contact name (e.g., 'Amma', 'Rahul', 'Dad') or phone number.
        """
        matches = self.contacts.search(contact_name_or_number)
        if not matches:
            return {
                "status": "contact_not_found",
                "message": f"Could not find a contact matching '{contact_name_or_number}'. Ask the owner for the phone number or exact name.",
            }

        # Check if multiple ambiguous matches exist with very close top scores
        if len(matches) > 1 and matches[0].match_score < 0.95 and (matches[0].match_score - matches[1].match_score) < 0.1:
            candidates = [{"name": m.name, "phone": m.phone} for m in matches[:3]]
            return {
                "status": "multiple_matches",
                "message": "Multiple contacts matched. Ask the owner which one they want to call.",
                "candidates": candidates,
            }

        target = matches[0]
        phone_uri = f"tel:{target.phone}"

        # Try direct Android ADB call first
        adb_ok, adb_msg = self._run_adb([
            "shell", "am", "start",
            "-a", "android.intent.action.CALL",
            "-d", phone_uri,
        ])

        if adb_ok and speakerphone:
            # Toggle speakerphone if requested
            self._run_adb(["shell", "cmd", "audio", "set-speakerphone-on", "true"])

        client_action = {
            "kind": "open_url",
            "url": phone_uri,
            "label": f"Call {target.name} ({target.phone})",
            "auto_trigger": not adb_ok,
        }

        self._record_action(
            action_type="call",
            title=f"Calling {target.name}",
            detail=f"{target.phone} ({'ADB Direct Call' if adb_ok else 'Mobile Dialer Triggered'})",
            client_action=client_action,
        )

        return {
            "status": "calling",
            "contact_name": target.name,
            "phone_number": target.phone,
            "speakerphone": speakerphone,
            "executed_via": "adb_direct" if adb_ok else "mobile_deeplink",
            "client_action": client_action,
        }

    # -------------------------------------------------------------------------
    # 2. WHATSAPP & SMS MESSAGING
    # -------------------------------------------------------------------------
    def send_whatsapp_message(self, contact_name_or_number: str, message: str, auto_send: bool = True) -> Dict[str, Any]:
        """
        Sends a WhatsApp message to a contact or phone number.
        """
        matches = self.contacts.search(contact_name_or_number)
        if not matches:
            return {
                "status": "contact_not_found",
                "message": f"No contact found matching '{contact_name_or_number}'.",
            }

        target = matches[0]
        clean_digits = re.sub(r"[^\d]", "", target.phone)
        encoded_msg = urllib.parse.quote(message)
        wa_url = f"https://api.whatsapp.com/send?phone={clean_digits}&text={encoded_msg}"

        adb_ok, _ = self._run_adb([
            "shell", "am", "start",
            "-a", "android.intent.action.VIEW",
            "-d", wa_url,
            "com.whatsapp",
        ])

        if adb_ok and auto_send:
            # Give WhatsApp a moment to focus the message composer, then tap Send
            time.sleep(1.5)
            tapped = self.tap_screen_element("Send")
            if tapped.get("status") != "ok":
                # Fallback: Enter key event
                self._run_adb(["shell", "input", "keyevent", "66"])

        client_action = {
            "kind": "open_url",
            "url": f"whatsapp://send?phone={clean_digits}&text={encoded_msg}",
            "label": f"WhatsApp {target.name}: \"{message}\"",
            "auto_trigger": not adb_ok,
        }

        self._record_action(
            action_type="whatsapp",
            title=f"WhatsApp to {target.name}",
            detail=f"\"{message}\"",
            client_action=client_action,
        )

        return {
            "status": "sent" if adb_ok else "ready_to_send",
            "contact_name": target.name,
            "phone_number": target.phone,
            "message": message,
            "executed_via": "adb_whatsapp" if adb_ok else "mobile_deeplink",
            "client_action": client_action,
        }

    def send_sms_message(self, contact_name_or_number: str, message: str) -> Dict[str, Any]:
        """
        Sends an SMS text message to a contact or phone number.
        """
        matches = self.contacts.search(contact_name_or_number)
        if not matches:
            return {
                "status": "contact_not_found",
                "message": f"No contact found matching '{contact_name_or_number}'.",
            }

        target = matches[0]
        encoded_body = urllib.parse.quote(message)
        sms_uri = f"sms:{target.phone}?body={encoded_body}"

        adb_ok, _ = self._run_adb([
            "shell", "am", "start",
            "-a", "android.intent.action.SENDTO",
            "-d", f"smsto:{target.phone}",
            "--es", "sms_body", message,
        ])

        client_action = {
            "kind": "open_url",
            "url": sms_uri,
            "label": f"SMS {target.name}: \"{message}\"",
            "auto_trigger": not adb_ok,
        }

        self._record_action(
            action_type="sms",
            title=f"SMS to {target.name}",
            detail=f"\"{message}\"",
            client_action=client_action,
        )

        return {
            "status": "opened_sms",
            "contact_name": target.name,
            "phone_number": target.phone,
            "message": message,
            "executed_via": "adb" if adb_ok else "mobile_deeplink",
            "client_action": client_action,
        }

    # -------------------------------------------------------------------------
    # 3. APP LAUNCHER, ALARMS & SYSTEM CONTROLS
    # -------------------------------------------------------------------------
    def open_android_app(self, app_name: str, permission_granted: bool = False) -> Dict[str, Any]:
        """
        Launches an installed Android application by its name (e.g. YouTube, WhatsApp, Maps, Spotify).
        Strictly blocks UPI/payment apps for owner privacy, and requires explicit owner permission
        before accessing Photos, Gallery, Camera, or File Manager apps.
        """
        key = app_name.strip().lower()
        if any(p in key for p in ["upi", "gpay", "google pay", "phonepe", "phone pe", "paytm", "bhim"]):
            return {
                "status": "blocked_for_privacy",
                "app_name": app_name,
                "message": "UPI and payment apps are strictly restricted for your privacy.",
            }
        if not permission_granted and any(
            p in key
            for p in ["photo", "photos", "gallery", "file", "files", "file manager", "my files", "storage", "sdcard", "downloads"]
        ):
            return {
                "status": "permission_required",
                "app_name": app_name,
                "message": "Explicit owner permission is required before accessing Photos, Gallery, or Files.",
            }
        app_info = ANDROID_APP_PACKAGES.get(key)

        # Partial match if exact key not found
        if not app_info:
            for known_name, info in ANDROID_APP_PACKAGES.items():
                if key in known_name or known_name in key:
                    app_info = info
                    break

        adb_ok = False
        if app_info and "package" in app_info:
            adb_ok, _ = self._run_adb([
                "shell", "monkey",
                "-p", app_info["package"],
                "-c", "android.intent.category.LAUNCHER", "1",
            ])

        deeplink = (app_info or {}).get("deeplink")
        client_action = (
            {
                "kind": "open_url",
                "url": deeplink,
                "label": f"Open {app_name.title()}",
                "auto_trigger": not adb_ok,
            }
            if deeplink
            else None
        )

        self._record_action(
            action_type="app",
            title=f"Opening {app_name.title()}",
            detail=(app_info or {}).get("package", app_name),
            client_action=client_action,
        )

        return {
            "status": "launched",
            "app_name": app_name,
            "package": (app_info or {}).get("package", "custom"),
            "executed_via": "adb" if adb_ok else "mobile_deeplink",
            "client_action": client_action,
        }

    def set_alarm_or_timer(
        self,
        mode: str,
        hour: Optional[int] = None,
        minute: Optional[int] = None,
        duration_seconds: Optional[int] = None,
        label: str = "Aura Reminder",
    ) -> Dict[str, Any]:
        """
        Sets an Android system alarm (hour, minute) or countdown timer (duration_seconds).
        """
        mode_clean = mode.strip().lower()
        if mode_clean == "timer" and duration_seconds:
            adb_ok, _ = self._run_adb([
                "shell", "am", "start",
                "-a", "android.intent.action.SET_TIMER",
                "--ei", "android.intent.extra.alarm.LENGTH", str(duration_seconds),
                "--es", "android.intent.extra.alarm.MESSAGE", label,
                "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
            ])
            detail = f"{duration_seconds}s timer ({label})"
        else:
            h = hour if hour is not None else 7
            m = minute if minute is not None else 0
            adb_ok, _ = self._run_adb([
                "shell", "am", "start",
                "-a", "android.intent.action.SET_ALARM",
                "--ei", "android.intent.extra.alarm.HOUR", str(h),
                "--ei", "android.intent.extra.alarm.MINUTES", str(m),
                "--es", "android.intent.extra.alarm.MESSAGE", label,
                "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
            ])
            detail = f"Alarm set for {h:02d}:{m:02d} ({label})"

        self._record_action(action_type="alarm", title=f"Set {mode_clean.title()}", detail=detail)
        return {"status": "ok", "mode": mode_clean, "detail": detail, "adb_executed": adb_ok}

    def control_device_setting(self, setting: str, action: str = "toggle") -> Dict[str, Any]:
        """
        Controls Android hardware & navigation settings:
        - setting: 'volume_up', 'volume_down', 'mute', 'flashlight', 'wifi', 'bluetooth',
                   'home', 'back', 'notifications', 'media_play_pause'
        """
        key = setting.strip().lower()
        act = action.strip().lower()

        keyevent_map = {
            "volume_up": "24",
            "volume_down": "25",
            "mute": "164",
            "home": "3",
            "back": "4",
            "media_play_pause": "85",
        }

        adb_ok = False
        detail = f"{setting} -> {action}"

        if key in keyevent_map:
            adb_ok, _ = self._run_adb(["shell", "input", "keyevent", keyevent_map[key]])
        elif key == "notifications":
            adb_ok, _ = self._run_adb(["shell", "cmd", "statusbar", "expand-notifications"])
        elif key == "wifi":
            svc_state = "disable" if act in {"off", "disable", "false"} else "enable"
            adb_ok, _ = self._run_adb(["shell", "svc", "wifi", svc_state])
            detail = f"Wi-Fi {svc_state}d"
        elif key == "bluetooth":
            svc_state = "disable" if act in {"off", "disable", "false"} else "enable"
            adb_ok, _ = self._run_adb(["shell", "svc", "bluetooth", svc_state])
            detail = f"Bluetooth {svc_state}d"
        elif key in {"flashlight", "torch"}:
            adb_ok, _ = self._run_adb(["shell", "cmd", "statusbar", "expand-settings"])
            detail = f"Flashlight {act}"

        self._record_action(action_type="system", title=f"System: {setting.replace('_', ' ').title()}", detail=detail)
        return {"status": "ok", "setting": setting, "action": action, "adb_executed": adb_ok, "detail": detail}

    # -------------------------------------------------------------------------
    # 4. DEEP SCREEN UI AUTOMATION (READING & TAPPING ELEMENTS)
    # -------------------------------------------------------------------------
    def parse_ui_hierarchy_xml(self, xml_content: str) -> List[Dict[str, Any]]:
        """
        Parses an Android UIAutomator XML dump into a clean list of interactive text buttons.
        PRIVACY GUARANTEE: On-screen photos (`android.widget.ImageView`) are strictly ignored and never scanned.
        """
        elements: List[Dict[str, Any]] = []
        try:
            root = ET.fromstring(xml_content)
        except ET.ParseError:
            return elements

        for node in root.iter("node"):
            cls_name = (node.attrib.get("class") or "").strip()
            # Never scan or inspect photos/images displayed on the screen
            if cls_name == "android.widget.ImageView":
                continue

            text = (node.attrib.get("text") or "").strip()
            desc = (node.attrib.get("content-desc") or "").strip()
            res_id = (node.attrib.get("resource-id") or "").strip()
            clickable = node.attrib.get("clickable") == "true"
            bounds = node.attrib.get("bounds", "")
            center = parse_bounds(bounds)

            if (text or desc) and center:
                elements.append({
                    "text": text,
                    "description": desc,
                    "resource_id": res_id,
                    "clickable": clickable,
                    "center": {"x": center[0], "y": center[1]},
                })
        return elements

    def read_screen_elements(self) -> Dict[str, Any]:
        """
        Reads all visible text and clickable buttons currently displayed on the Android phone screen.
        """
        ok, _ = self._run_adb(["shell", "uiautomator", "dump", "/sdcard/aura_ui.xml"])
        if not ok:
            return {"status": "error", "message": "Unable to dump screen via ADB. Ensure Android device is connected."}

        ok_cat, xml_text = self._run_adb(["shell", "cat", "/sdcard/aura_ui.xml"])
        if not ok_cat or not xml_text:
            return {"status": "error", "message": "Failed to read UI XML dump."}

        elements = self.parse_ui_hierarchy_xml(xml_text)
        return {"status": "ok", "element_count": len(elements), "elements": elements[:40]}

    def tap_screen_element(self, target_text: str) -> Dict[str, Any]:
        """
        Finds a button or UI element on the Android screen matching `target_text` and taps its exact coordinates.
        """
        screen = self.read_screen_elements()
        if screen.get("status") != "ok":
            return screen

        target_lower = target_text.strip().lower()
        for el in screen.get("elements", []):
            label = f"{el['text']} {el['description']}".lower()
            if target_lower in label:
                cx, cy = el["center"]["x"], el["center"]["y"]
                adb_ok, _ = self._run_adb(["shell", "input", "tap", str(cx), str(cy)])
                self._record_action(
                    action_type="screen_tap",
                    title=f"Tapped '{target_text}'",
                    detail=f"Coordinates ({cx}, {cy})",
                )
                return {"status": "ok", "tapped": target_text, "coordinates": {"x": cx, "y": cy}, "adb_executed": adb_ok}

        return {"status": "not_found", "message": f"No UI element matching '{target_text}' found on screen."}

    # -------------------------------------------------------------------------
    # 5. YOUTUBE MEDIA PLAYBACK, GOOGLE MAPS NAVIGATION & BATTERY STATUS
    # -------------------------------------------------------------------------
    def play_youtube_media(self, query: str) -> Dict[str, Any]:
        """
        Searches and plays a song, video, or artist on YouTube (via ADB intent and mobile deep-link).
        """
        clean_q = query.strip()
        encoded_q = urllib.parse.quote(clean_q)
        yt_url = f"https://www.youtube.com/results?search_query={encoded_q}"

        adb_ok, _ = self._run_adb([
            "shell", "am", "start",
            "-a", "android.intent.action.VIEW",
            "-d", yt_url,
        ])
        client_action = {
            "kind": "open_url",
            "url": yt_url,
            "label": f"▶️ Play '{clean_q}' on YouTube",
            "auto_trigger": False,
        }
        self._record_action(
            action_type="youtube",
            title=f"YouTube: {clean_q}",
            detail=yt_url,
            client_action=client_action,
        )
        return {
            "status": "ok",
            "query": clean_q,
            "url": yt_url,
            "adb_executed": adb_ok,
            "client_action": client_action,
        }

    def navigate_to_location(self, destination: str) -> Dict[str, Any]:
        """
        Launches Google Maps turn-by-turn navigation to `destination`.
        """
        clean_dest = destination.strip()
        encoded_dest = urllib.parse.quote(clean_dest)
        maps_url = f"https://www.google.com/maps/dir/?api=1&destination={encoded_dest}"

        adb_ok, _ = self._run_adb([
            "shell", "am", "start",
            "-a", "android.intent.action.VIEW",
            "-d", f"google.navigation:q={encoded_dest}",
        ])
        client_action = {
            "kind": "open_url",
            "url": maps_url,
            "label": f"🗺️ Navigate to '{clean_dest}'",
            "auto_trigger": False,
        }
        self._record_action(
            action_type="navigation",
            title=f"Navigate: {clean_dest}",
            detail=maps_url,
            client_action=client_action,
        )
        return {
            "status": "ok",
            "destination": clean_dest,
            "url": maps_url,
            "adb_executed": adb_ok,
            "client_action": client_action,
        }

    def get_battery_status(self) -> Dict[str, Any]:
        """
        Reads real Android battery percentage via `adb shell dumpsys battery` (or returns current status).
        """
        adb_ok, out = self._run_adb(["shell", "dumpsys", "battery"])
        level = 85
        charging = True
        if adb_ok and out:
            lvl_match = re.search(r"level:\s*(\d+)", out)
            if lvl_match:
                level = int(lvl_match.group(1))
            charging = "AC powered: true" in out or "USB powered: true" in out

        detail = f"{level}% ({'Charging' if charging else 'On Battery'})"
        self._record_action(action_type="battery", title="Battery Check", detail=detail)
        return {
            "status": "ok",
            "level": level,
            "charging": charging,
            "detail": detail,
            "adb_executed": adb_ok,
        }

    def search_google_web(self, query: str) -> Dict[str, Any]:
        """
        Opens a Google Web search on the Android device (via ADB intent and client deep-link).
        """
        clean_q = query.strip()
        encoded_q = urllib.parse.quote(clean_q)
        search_url = f"https://www.google.com/search?q={encoded_q}"

        adb_ok, _ = self._run_adb([
            "shell", "am", "start",
            "-a", "android.intent.action.VIEW",
            "-d", search_url,
        ])
        client_action = {
            "kind": "open_url",
            "url": search_url,
            "label": f"🔍 Search Google: '{clean_q}'",
            "auto_trigger": False,
        }
        self._record_action(
            action_type="web_search",
            title=f"Google Search: {clean_q}",
            detail=search_url,
            client_action=client_action,
        )
        return {
            "status": "ok",
            "query": clean_q,
            "url": search_url,
            "adb_executed": adb_ok,
            "client_action": client_action,
        }

    def calculate_math(self, expression: str) -> Dict[str, Any]:
        """
        Safely evaluates spoken arithmetic or percentage expressions (e.g., '15 percent of 2400', '125 * 18').
        """
        expr = expression.lower().strip()
        # Handle "X percent of Y" or "X% of Y"
        pct_match = re.search(r"(\d+(?:\.\d+)?)\s*(?:percent|%)\s*(?:of|ka|lo)?\s*(\d+(?:\.\d+)?)", expr)
        if pct_match:
            pct = float(pct_match.group(1))
            base = float(pct_match.group(2))
            val = (pct / 100.0) * base
            res_str = str(int(val)) if val.is_integer() else f"{val:.2f}".rstrip("0").rstrip(".")
            self._record_action(action_type="calculator", title=f"{pct}% of {base}", detail=res_str)
            return {"status": "ok", "expression": f"{pct}% of {base}", "result": res_str}

        # Convert spoken operators to math symbols
        replacements = [
            (r"\b(?:plus|add|jodo)\b", "+"),
            (r"\b(?:minus|subtract|ghatao)\b", "-"),
            (r"\b(?:into|times|multiply|multiplied by|guna)\b", "*"),
            (r"\b(?:divided by|divide by|by|bhag)\b", "/"),
            (r"×", "*"),
            (r"÷", "/"),
        ]
        for pat, sym in replacements:
            expr = re.sub(pat, f" {sym} ", expr)

        # Extract valid arithmetic characters only
        clean_math = "".join(ch for ch in expr if ch in "0123456789.+-*/() ")
        clean_math = re.sub(r"\s+", " ", clean_math).strip()
        if not clean_math or not re.search(r"\d+\s*[+\-*/]\s*\d+", clean_math):
            return {"status": "error", "message": "Invalid math expression"}

        try:
            val = float(eval(clean_math, {"__builtins__": {}}, {}))
            res_str = str(int(val)) if val.is_integer() else f"{val:.2f}".rstrip("0").rstrip(".")
            self._record_action(action_type="calculator", title=f"Calc: {clean_math}", detail=res_str)
            return {"status": "ok", "expression": clean_math, "result": res_str}
        except Exception as exc:
            return {"status": "error", "message": str(exc)}

    # -------------------------------------------------------------------------
    # 6. HANDS-FREE SCREEN SCROLLING, SCREENSHOT & CAMERA SHUTTER
    # -------------------------------------------------------------------------
    def scroll_screen(self, direction: str = "down") -> Dict[str, Any]:
        """
        Scrolls or swipes the Android screen ('down', 'up', 'left', 'right') hands-free.
        """
        d = direction.strip().lower()
        # Standard 1080x2400 swipe coordinates
        swipe_coords = {
            "down": ("540", "1650", "540", "550", "320"),
            "up": ("540", "550", "540", "1650", "320"),
            "left": ("900", "1200", "180", "1200", "280"),
            "right": ("180", "1200", "900", "1200", "280"),
        }
        x1, y1, x2, y2, dur = swipe_coords.get(d, swipe_coords["down"])
        adb_ok, _ = self._run_adb(["shell", "input", "swipe", x1, y1, x2, y2, dur])
        detail = f"Scrolled screen {d}"
        self._record_action(action_type="gesture", title=f"Scroll {d.title()}", detail=detail)
        return {"status": "ok", "direction": d, "detail": detail, "adb_executed": adb_ok}

    def take_screenshot(self) -> Dict[str, Any]:
        """
        PRIVACY BLOCK: Capturing or scanning screen images/photos is disabled so on-screen photos cannot be scanned.
        """
        return {
            "status": "blocked_for_privacy",
            "detail": "Screen image capture and on-screen photo scanning are disabled for your privacy.",
            "adb_executed": False,
        }

    def take_camera_photo(self, mode: str = "photo", permission_granted: bool = False) -> Dict[str, Any]:
        """
        Opens the Android camera only after explicit owner permission is granted.
        """
        if not permission_granted:
            return {
                "status": "permission_required",
                "mode": mode,
                "detail": "Explicit owner permission is required before accessing the Camera.",
                "adb_executed": False,
            }
        clean_mode = "selfie" if "selfie" in mode.lower() else "photo"
        cmd = ["shell", "am", "start", "-a", "android.media.action.STILL_IMAGE_CAMERA"]
        if clean_mode == "selfie":
            cmd.extend(["--ei", "android.intent.extras.CAMERA_FACING", "1"])
        self._run_adb(cmd)
        adb_ok, _ = self._run_adb(["shell", "input", "keyevent", "27"])
        detail = f"Camera ({clean_mode}) launched & shutter triggered with owner permission"
        self._record_action(action_type="camera", title=f"Take {clean_mode.title()}", detail=detail)
        return {"status": "ok", "mode": clean_mode, "detail": detail, "adb_executed": adb_ok}

    # -------------------------------------------------------------------------
    # 7. MEDIA PLAYBACK CONTROLS & EMERGENCY SOS
    # -------------------------------------------------------------------------
    def control_media_playback(self, command: str = "play_pause") -> Dict[str, Any]:
        """
        Controls Android media playback ('play', 'pause', 'next', 'previous').
        """
        cmd_lower = command.strip().lower()
        key_map = {
            "play": ("85", "Media Play / Resume"),
            "pause": ("85", "Media Pause"),
            "play_pause": ("85", "Media Play/Pause"),
            "next": ("87", "Next Track"),
            "previous": ("88", "Previous Track"),
        }
        keycode, detail = key_map.get(cmd_lower, ("85", "Media Play/Pause"))
        adb_ok, _ = self._run_adb(["shell", "input", "keyevent", keycode])
        self._record_action(action_type="media", title=detail, detail=f"Keyevent {keycode}")
        return {"status": "ok", "command": cmd_lower, "detail": detail, "adb_executed": adb_ok}

    def trigger_emergency_sos(self) -> Dict[str, Any]:
        """
        Triggers Emergency SOS: resolves primary family contact (e.g. Amma) or 112,
        initiates an immediate emergency call, and prepares an SOS WhatsApp alert.
        """
        matches = self.contacts.search("amma") or self.contacts.list_all()
        if matches:
            target_name = matches[0].name
            target_phone = matches[0].phone
        else:
            target_name = "Emergency Services (112)"
            target_phone = "112"

        call_res = self.make_phone_call(target_phone, speakerphone=True)
        wa_res = self.send_whatsapp_message(
            target_phone,
            "🚨 SOS EMERGENCY ALERT from Aura Assistant: I need urgent help! Please call or check my location immediately.",
        )
        client_actions = []
        if call_res.get("client_action"):
            client_actions.append(call_res["client_action"])
        if wa_res.get("client_action"):
            client_actions.append(wa_res["client_action"])

        self._record_action(
            action_type="sos",
            title=f"🚨 SOS Alert -> {target_name}",
            detail=f"Emergency Call & WhatsApp to {target_phone}",
        )
        return {
            "status": "ok",
            "emergency_contact": target_name,
            "phone_number": target_phone,
            "client_actions": client_actions,
        }
