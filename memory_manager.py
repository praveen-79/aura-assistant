"""
Persistent Owner Memory Manager for Aura AI Assistant.
Stores personal facts, preferences, and custom reminders about the phone owner
so Aura remembers them across sessions and injects them into Gemini's context.
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any, Dict, List


MEMORY_FILE = Path(__file__).parent / "owner_memory.json"


class MemoryManager:
    def __init__(self, memory_path: Path = MEMORY_FILE):
        self.memory_path = memory_path
        self.memories: List[Dict[str, str]] = self._load_memories()

    def _load_memories(self) -> List[Dict[str, str]]:
        if not self.memory_path.exists():
            return [
                {"key": "assistant_role", "value": "Personal multilingual Android voice & automation assistant", "updated_at": "default"}
            ]
        try:
            return json.loads(self.memory_path.read_text(encoding="utf-8"))
        except Exception:
            return []

    def save_memories(self) -> None:
        self.memory_path.write_text(
            json.dumps(self.memories, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

    def remember_fact(self, key: str, value: str) -> Dict[str, Any]:
        """Saves or updates a personal fact or preference about the owner."""
        clean_key = key.strip().lower()
        entry = {
            "key": clean_key,
            "value": value.strip(),
            "updated_at": time.strftime("%Y-%m-%d %H:%M"),
        }
        for idx, existing in enumerate(self.memories):
            if existing.get("key") == clean_key:
                self.memories[idx] = entry
                self.save_memories()
                return {"status": "updated", "memory": entry}

        self.memories.append(entry)
        self.save_memories()
        return {"status": "saved", "memory": entry}

    def list_memories(self) -> List[Dict[str, str]]:
        return self.memories

    def format_for_prompt(self) -> str:
        if not self.memories:
            return ""
        bullets = "\n".join(f"- {m['key']}: {m['value']}" for m in self.memories)
        return f"\nOWNER PERSONAL MEMORY & PREFERENCES:\n{bullets}\n"
