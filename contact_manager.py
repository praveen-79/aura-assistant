"""
Smart Contact Manager for Aura AI Assistant.
Handles Indian speech-to-text quirks (extra gaps between syllables, fused postpositions
like 'ammako'/'rahulbhai', and Indic script transliteration across Hindi, Tamil, Telugu, Bengali).
"""

from __future__ import annotations

import difflib
import json
import re
import subprocess
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import List, Optional


CONTACTS_FILE = Path(__file__).parent / "contacts.json"


# Phonetic mapping for common Devanagari / Tamil / Telugu characters & words into Latin
INDIC_WORD_MAP = {
    "अम्मा": "amma",
    "माँ": "maa",
    "मम्मी": "mummy",
    "पापा": "papa",
    "अप्पा": "appa",
    "बाबा": "baba",
    "राहुल": "rahul",
    "शर्मा": "sharma",
    "प्रिया": "priya",
    "रोहित": "rohit",
    "भाई": "bhai",
    "दीदी": "didi",
    "जी": "ji",
    "அம்மா": "amma",
    "அப்பா": "appa",
    "ராகுல்": "rahul",
    "பிரியா": "priya",
    "అమ్మ": "amma",
    "నాన్న": "nanna",
    "రాహుల్": "rahul",
    "ప్రియా": "priya",
    "రోహిత్": "rohit",
    "అన్న": "anna",
    "అక్క": "akka",
    "గారు": "garu",
    "మా": "maa",
    "বাবা": "baba",
}

DEVANAGARI_CHAR_MAP = {
    "अ": "a", "आ": "a", "इ": "i", "ई": "i", "उ": "u", "ऊ": "u", "ए": "e", "ऐ": "ai", "ओ": "o", "औ": "au",
    "ा": "a", "ि": "i", "ी": "i", "ु": "u", "ू": "u", "े": "e", "ै": "ai", "ो": "o", "ौ": "au",
    "ं": "n", "ँ": "n", "्": "",
    "क": "ka", "ख": "kha", "ग": "ga", "घ": "gha",
    "च": "cha", "छ": "chha", "ज": "ja", "झ": "jha",
    "ट": "ta", "ठ": "tha", "ड": "da", "ढ": "dha", "ण": "na",
    "त": "ta", "थ": "tha", "द": "da", "ध": "dha", "न": "na",
    "प": "pa", "फ": "pha", "ब": "ba", "भ": "bha", "म": "ma",
    "य": "ya", "र": "ra", "ल": "la", "व": "va",
    "श": "sha", "ष": "sha", "स": "sa", "ह": "ha",
}

TELUGU_CHAR_MAP = {
    "అ": "a", "ఆ": "aa", "ఇ": "i", "ఈ": "ee", "ఉ": "u", "ఊ": "oo", "ఋ": "ru",
    "ఎ": "e", "ఏ": "e", "ఐ": "ai", "ఒ": "o", "ఓ": "o", "ఔ": "au",
    "ా": "a", "ి": "i", "ీ": "i", "ు": "u", "ూ": "u", "ృ": "ru",
    "ె": "e", "ే": "e", "ై": "ai", "ొ": "o", "ో": "o", "ౌ": "au",
    "ం": "m", "ః": "h", "్": "",
    "క": "ka", "ఖ": "kha", "గ": "ga", "ఘ": "gha", "ఙ": "nga",
    "చ": "cha", "ఛ": "chha", "జ": "ja", "ఝ": "jha", "ఞ": "nya",
    "ట": "ta", "ఠ": "tha", "డ": "da", "ఢ": "dha", "ణ": "na",
    "త": "ta", "థ": "tha", "ద": "da", "ధ": "dha", "న": "na",
    "ప": "pa", "ఫ": "pha", "బ": "ba", "భ": "bha", "మ": "ma",
    "య": "ya", "ర": "ra", "ఱ": "ra", "ల": "la", "ళ": "la", "వ": "va",
    "శ": "sha", "ష": "sha", "స": "sa", "హ": "ha",
}

TAMIL_CHAR_MAP = {
    "அ": "a", "ஆ": "aa", "இ": "i", "ஈ": "ee", "உ": "u", "ஊ": "oo",
    "எ": "e", "ஏ": "e", "ஐ": "ai", "ஒ": "o", "ஓ": "o", "ஔ": "au",
    "ா": "a", "ி": "i", "ீ": "i", "ு": "u", "ூ": "u",
    "ெ": "e", "ே": "e", "ை": "ai", "ொ": "o", "ோ": "o", "ௌ": "au",
    "ம்": "m", "்": "", "ं": "m",
    "க": "ka", "ங": "nga", "ச": "sa", "ஞ": "nya", "ட": "da", "ண": "na",
    "த": "tha", "ந": "na", "ன": "na", "ப": "pa", "ம": "ma",
    "ய": "ya", "ர": "ra", "ற": "ra", "ல": "la", "ள": "la", "ழ": "zha", "வ": "va",
    "ஜ": "ja", "ஷ": "sha", "ஸ": "sa", "ஹ": "ha",
}


INDIC_MATRAS = {
    # Devanagari matras + halant
    "ा": "aa", "ि": "i", "ी": "ee", "ु": "u", "ू": "oo", "ृ": "ri",
    "े": "e", "ै": "ai", "ो": "o", "ौ": "au", "्": "",
    # Telugu matras + virama
    "ా": "aa", "ి": "i", "ీ": "ee", "ు": "u", "ూ": "u", "ృ": "ru",
    "ె": "e", "ే": "e", "ై": "ai", "ొ": "o", "ో": "o", "ౌ": "au", "్": "",
    # Tamil matras + pulli
    "ா": "aa", "ி": "i", "ீ": "ee", "ு": "u", "ூ": "u",
    "ெ": "e", "ே": "e", "ை": "ai", "ொ": "o", "ோ": "o", "ௌ": "au", "்": "",
}


def transliterate_indic_to_latin(text: str) -> str:
    """Converts Indic script words and Devanagari/Telugu/Tamil characters to natural phonetic Latin."""
    out = text
    for indic_word, latin_word in INDIC_WORD_MAP.items():
        out = out.replace(indic_word, f" {latin_word} ")

    chars: List[str] = []
    for ch in out:
        if ch in INDIC_MATRAS:
            if chars and chars[-1].endswith("a"):
                chars[-1] = chars[-1][:-1]
            chars.append(INDIC_MATRAS[ch])
        elif ch in DEVANAGARI_CHAR_MAP:
            chars.append(DEVANAGARI_CHAR_MAP[ch])
        elif ch in TELUGU_CHAR_MAP:
            chars.append(TELUGU_CHAR_MAP[ch])
        elif ch in TAMIL_CHAR_MAP:
            chars.append(TAMIL_CHAR_MAP[ch])
        else:
            chars.append(ch)
    return "".join(chars)


def clean_text_for_tts(text: str) -> str:
    """Strips parenthetical phone numbers, emojis, symbols, and Markdown so TTS engine speaks naturally without failure."""
    cleaned = re.sub(r"\(\s*\+?\d[\d\s-]{5,}\s*\)", "", text)
    cleaned = re.sub(r"\(\s*(?:Mom|Dad|Brother|Sister|Aura)\s*\)", "", cleaned, flags=re.IGNORECASE)
    # Strip URLs
    cleaned = re.sub(r"https?://\S+|whatsapp://\S+", "", cleaned)
    # Strip emojis and decorative symbols
    cleaned = re.sub(r"[\U00010000-\U0010ffff]", "", cleaned)
    cleaned = re.sub(r"[\u2600-\u27BF\u2B50-\u2B55\u200D\uFE0F]", "", cleaned)
    cleaned = re.sub(r"[⚡✅📖🪟📸💬🔋🔊⏲⏰🔦🎵🗺🚀🤖🌸📘🧮•*`_#~]", "", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned



def collapse_stt_gaps(text: str) -> str:
    """
    Fixes Indian STT word-splitting and word-fusing:
    - Collapses single-character or split syllable gaps: 'r a h u l' -> 'rahul', 'am ma' -> 'amma'
    - Splits fused postpositions: 'ammako' -> 'amma ko', 'rahulbhai' -> 'rahul bhai'
    """
    s = transliterate_indic_to_latin(text.lower()).strip()
    # Collapse single-letter spaced sequences like 'a m m a'
    s = re.sub(r"\b([a-z])\s+(?=[a-z]\b)", r"\1", s)
    # Split fused Indian postpositions/honorifics attached to the end of a name
    s = re.sub(r"([a-z]{3,})(ko|ku|ki|bhai|bhaiya|didi|anna|akka|ji|garu)\b", r"\1 \2", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def normalize_phonetic(text: str) -> str:
    """
    Normalizes spoken Indian/international names and phonetic variations
    so 'Raahul', 'Rahul bhai', 'राहुल', 'rahul', 'am ma' match reliably.
    """
    collapsed = collapse_stt_gaps(text)
    cleaned = re.sub(r"[^\w\s]", "", collapsed).strip()
    honorifics = {
        "ji", "bhai", "bhaiya", "anna", "akka", "didi", "garu", "sir", "madam",
        "ko", "ku", "ki", "ka", "se", "to", "call", "phone", "karo", "lagao", "pannu", "chey",
    }
    tokens = [t for t in cleaned.split() if t not in honorifics]
    base = " ".join(tokens) if tokens else cleaned

    # Also test joining adjacent short syllables (e.g., "ra hul" -> "rahul", "am ma" -> "amma")
    joined_tokens = "".join(tokens) if len(tokens) == 2 and all(len(t) <= 4 for t in tokens) else base

    # Collapse repeated letters ('ammaaa' -> 'ama', 'raahul' -> 'rahul')
    norm = re.sub(r"([a-z])\1+", r"\1", joined_tokens)
    norm = norm.replace("ph", "f").replace("bh", "b").replace("sh", "s").replace("ch", "c").replace("v", "w")
    # Strip trailing schwa 'a' from Devanagari transliteration (e.g. 'rahula' -> 'rahul')
    if len(norm) > 4 and norm.endswith("a") and not norm.endswith("ma") and not norm.endswith("ya"):
        norm = norm[:-1]
    return norm


@dataclass
class Contact:
    name: str
    phone: str
    aliases: List[str]
    relationship: str = ""
    match_score: float = 0.0

    def to_dict(self) -> dict:
        return asdict(self)


class ContactManager:
    def __init__(self, contacts_path: Path = CONTACTS_FILE):
        self.contacts_path = contacts_path
        self.contacts: List[Contact] = self._load_contacts()

    def _load_contacts(self) -> List[Contact]:
        if not self.contacts_path.exists():
            return []
        try:
            raw = json.loads(self.contacts_path.read_text(encoding="utf-8"))
            return [
                Contact(
                    name=item["name"],
                    phone=item["phone"],
                    aliases=item.get("aliases", []),
                    relationship=item.get("relationship", ""),
                )
                for item in raw
            ]
        except Exception:
            return []

    def save_contacts(self) -> None:
        data = [
            {
                "name": c.name,
                "phone": c.phone,
                "aliases": c.aliases,
                "relationship": c.relationship,
            }
            for c in self.contacts
        ]
        self.contacts_path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")

    def add_contact(self, name: str, phone: str, aliases: Optional[List[str]] = None, relationship: str = "") -> Contact:
        clean_phone = re.sub(r"[^\d+]", "", phone)
        name_clean = name.strip()
        name_lower = name_clean.lower()

        for idx, existing in enumerate(self.contacts):
            if existing.name.lower() == name_lower or name_lower in [a.lower() for a in existing.aliases]:
                merged_aliases = list(dict.fromkeys((aliases or []) + existing.aliases + [name_lower]))
                updated = Contact(
                    name=name_clean,
                    phone=clean_phone,
                    aliases=merged_aliases,
                    relationship=relationship or existing.relationship,
                    match_score=1.0,
                )
                self.contacts[idx] = updated
                self.save_contacts()
                return updated

        contact = Contact(
            name=name_clean,
            phone=clean_phone,
            aliases=aliases or [name_lower],
            relationship=relationship,
            match_score=1.0,
        )
        self.contacts.append(contact)
        self.save_contacts()
        return contact

    def sync_bulk_contacts(self, items: List[dict]) -> dict:
        imported = 0
        for item in items:
            name = str(item.get("name", "")).strip()
            phone = str(item.get("phone", "")).strip()
            if name and phone:
                self.add_contact(name=name, phone=phone)
                imported += 1
        return {"status": "ok", "imported_count": imported, "total_contacts": len(self.contacts)}

    def search(self, query: str, threshold: float = 0.55) -> List[Contact]:
        """
        Finds contacts matching a spoken name, relationship, alias, Indic script name,
        split-syllable STT output ('ra hul'), or raw phone number.
        """
        query_clean = collapse_stt_gaps(query)
        if not query_clean:
            return []

        digits_only = re.sub(r"[^\d+]", "", query_clean)
        if len(digits_only) >= 3 and digits_only.lstrip("+").isdigit():
            return [Contact(name=query.strip(), phone=digits_only, aliases=[], match_score=1.0)]

        query_phonetic = normalize_phonetic(query_clean)
        matches: List[Contact] = []

        for contact in self.contacts:
            candidates = [contact.name.lower(), contact.relationship.lower()] + [a.lower() for a in contact.aliases]
            best_score = 0.0

            for candidate in candidates:
                if not candidate:
                    continue
                cand_clean = collapse_stt_gaps(candidate)
                if query_clean == cand_clean or query.strip().lower() == candidate:
                    best_score = 1.0
                    break
                if query_clean in cand_clean or cand_clean in query_clean:
                    best_score = max(best_score, 0.92)

                cand_phonetic = normalize_phonetic(cand_clean)
                if query_phonetic and cand_phonetic:
                    if query_phonetic == cand_phonetic:
                        best_score = max(best_score, 0.96)
                    elif query_phonetic in cand_phonetic or cand_phonetic in query_phonetic:
                        best_score = max(best_score, 0.86)
                    else:
                        seq_ratio = difflib.SequenceMatcher(None, query_phonetic, cand_phonetic).ratio()
                        best_score = max(best_score, seq_ratio)

            if best_score >= threshold:
                matches.append(
                    Contact(
                        name=contact.name,
                        phone=contact.phone,
                        aliases=contact.aliases,
                        relationship=contact.relationship,
                        match_score=round(best_score, 3),
                    )
                )

        matches.sort(key=lambda c: c.match_score, reverse=True)
        return matches

    def sync_from_android_adb(self) -> dict:
        """Pulls real contacts from a connected Android phone using ADB content provider query."""
        try:
            cmd = [
                "adb",
                "shell",
                "content",
                "query",
                "--uri",
                "content://com.android.contacts/data/phones",
                "--projection",
                "display_name:data1",
            ]
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if res.returncode != 0:
                return {"status": "error", "message": res.stderr.strip() or "ADB device not connected"}

            imported = 0
            for line in res.stdout.splitlines():
                name_match = re.search(r"display_name=([^,]+)", line)
                phone_match = re.search(r"data1=([^\s,]+)", line)
                if name_match and phone_match:
                    name = name_match.group(1).strip()
                    phone = phone_match.group(1).strip()
                    if name and phone and name != "NULL":
                        self.add_contact(name=name, phone=phone)
                        imported += 1

            return {"status": "ok", "imported_count": imported, "total_contacts": len(self.contacts)}
        except FileNotFoundError:
            return {"status": "error", "message": "ADB is not installed or not in PATH."}
        except Exception as exc:
            return {"status": "error", "message": str(exc)}
