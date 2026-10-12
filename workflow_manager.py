"""
Multi-Step Workflow & Routine Engine for Aura AI Assistant.
Allows a single multilingual voice command (e.g. "I'm heading home", "Good night", "Gym time")
to execute a chain of Android automations in sequence.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

from android_automation import AndroidAutomationEngine


WORKFLOWS_FILE = Path(__file__).parent / "workflows.json"


class WorkflowManager:
    def __init__(self, automation: AndroidAutomationEngine, workflows_path: Path = WORKFLOWS_FILE):
        self.automation = automation
        self.workflows_path = workflows_path
        self.workflows: List[Dict[str, Any]] = self._load_workflows()

    def _load_workflows(self) -> List[Dict[str, Any]]:
        if not self.workflows_path.exists():
            return []
        try:
            return json.loads(self.workflows_path.read_text(encoding="utf-8"))
        except Exception:
            return []

    def save_workflows(self) -> None:
        self.workflows_path.write_text(
            json.dumps(self.workflows, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

    def list_workflows(self) -> List[Dict[str, Any]]:
        return self.workflows

    def add_workflow(
        self,
        name: str,
        triggers: List[str],
        steps: List[Dict[str, Any]],
        reply_en: str = "",
        reply_hi: str = "",
    ) -> Dict[str, Any]:
        wf_id = re.sub(r"[^a-z0-9_]", "_", name.strip().lower())
        wf = {
            "id": wf_id,
            "name": name.strip(),
            "triggers": [t.strip().lower() for t in triggers if t.strip()],
            "steps": steps,
            "reply_en": reply_en or f"Executed '{name}' routine ({len(steps)} steps).",
            "reply_hi": reply_hi or f"'{name}' रूटीन पूरा किया गया ({len(steps)} स्टेप्स)।",
        }
        for idx, existing in enumerate(self.workflows):
            if existing.get("id") == wf_id:
                self.workflows[idx] = wf
                self.save_workflows()
                return wf
        self.workflows.append(wf)
        self.save_workflows()
        return wf

    def match_workflow(self, text: str) -> Optional[Dict[str, Any]]:
        lower = text.strip().lower()
        for wf in self.workflows:
            if lower == wf.get("name", "").lower() or lower == wf.get("id", "").lower():
                return wf
            for trig in wf.get("triggers", []):
                if trig and trig in lower:
                    return wf
        return None

    def execute_workflow(self, workflow_name_or_trigger: str, lang: str = "en") -> Dict[str, Any]:
        wf = self.match_workflow(workflow_name_or_trigger)
        if not wf:
            return {
                "status": "not_found",
                "message": f"No routine found matching '{workflow_name_or_trigger}'.",
            }

        executed_steps: List[Dict[str, Any]] = []
        client_actions: List[Dict[str, Any]] = []

        for step in wf.get("steps", []):
            tool_name = step.get("tool", "")
            tool_args = step.get("args", {})
            fn = getattr(self.automation, tool_name, None)
            if callable(fn):
                res = fn(**tool_args)
                executed_steps.append({
                    "name": tool_name,
                    "args": tool_args,
                    "result": res,
                })
                if isinstance(res, dict) and res.get("client_action"):
                    client_actions.append(res["client_action"])

        reply = (
            wf.get(f"reply_{lang}")
            or (wf.get("reply_hi") if lang == "hi" else None)
            or wf.get("reply_en", f"Completed {wf['name']}.")
        )
        return {
            "status": "ok",
            "workflow_id": wf["id"],
            "workflow_name": wf["name"],
            "reply": reply,
            "executed_steps": executed_steps,
            "client_actions": client_actions,
        }
