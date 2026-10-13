"""
AuraAssistant - Deep Learning Intent Classifier Inference Engine.
Loads trained PyTorch neural network weights (`intent_model.pt`) and vocabulary.
Predicts intent class and confidence for any user command.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Dict, List, Optional, Tuple

ARTIFACTS_DIR = Path(__file__).parent / "model_artifacts"
MODEL_PATH = ARTIFACTS_DIR / "intent_model.pt"
VOCAB_PATH = ARTIFACTS_DIR / "vocab.json"


def clean_tokenize(text: str) -> List[str]:
    clean = re.sub(r"[^a-zA-Z0-9\s]", " ", text.lower())
    return [w for w in clean.split() if len(w) > 0]


class IntentClassifier:
    def __init__(self):
        self.model = None
        self.word_to_idx = {}
        self.intent_to_idx = {}
        self.idx_to_intent = {}
        self.is_loaded = False
        self._load_model()

    def _load_model(self):
        if not MODEL_PATH.exists() or not VOCAB_PATH.exists():
            return

        try:
            import torch
            import torch.nn as nn

            with open(VOCAB_PATH, "r", encoding="utf-8") as f:
                meta = json.load(f)

            self.word_to_idx = meta["word_to_idx"]
            self.intent_to_idx = meta["intent_to_idx"]
            self.idx_to_intent = {int(k): v for k, v in meta["idx_to_intent"].items()}

            class IntentClassifierNN(nn.Module):
                def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int, num_classes: int):
                    super().__init__()
                    self.embedding = nn.EmbeddingBag(vocab_size, embed_dim, sparse=False)
                    self.fc1 = nn.Linear(embed_dim, hidden_dim)
                    self.relu = nn.ReLU()
                    self.dropout = nn.Dropout(0.25)
                    self.fc2 = nn.Linear(hidden_dim, num_classes)

                def forward(self, text, offsets):
                    embedded = self.embedding(text, offsets)
                    h = self.relu(self.fc1(embedded))
                    h = self.dropout(h)
                    return self.fc2(h)

            self.model = IntentClassifierNN(
                len(self.word_to_idx),
                meta.get("embed_dim", 64),
                meta.get("hidden_dim", 48),
                meta.get("num_classes", len(self.idx_to_intent)),
            )

            state_dict = torch.load(MODEL_PATH, map_location=torch.device("cpu"))
            self.model.load_state_dict(state_dict)
            self.model.eval()
            self.is_loaded = True
        except Exception as e:
            print(f"[WARN] Failed to load PyTorch intent model: {e}")
            self.is_loaded = False

    def predict(self, text: str) -> Dict[str, any]:
        """
        Returns {
            'intent': str,
            'confidence': float,
            'probabilities': Dict[str, float]
        }
        """
        if not self.is_loaded or self.model is None:
            # Fallback heuristic
            return self._heuristic_fallback(text)

        import torch

        tokens = clean_tokenize(text)
        token_indices = [self.word_to_idx.get(w, self.word_to_idx.get("<UNK>", 1)) for w in tokens]
        if not token_indices:
            token_indices = [self.word_to_idx.get("<UNK>", 1)]

        with torch.no_grad():
            t_inp = torch.tensor(token_indices, dtype=torch.long)
            t_off = torch.tensor([0], dtype=torch.long)
            logits = self.model(t_inp, t_off)
            probs = torch.softmax(logits, dim=1).squeeze(0)
            conf, pred_idx = torch.max(probs, dim=0)

            intent_name = self.idx_to_intent.get(pred_idx.item(), "chat")
            all_probs = {self.idx_to_intent.get(i, str(i)): round(p.item(), 4) for i, p in enumerate(probs)}

            return {
                "intent": intent_name,
                "confidence": round(conf.item(), 4),
                "probabilities": all_probs,
                "model_type": "pytorch_deep_learning_nn",
            }

    def _heuristic_fallback(self, text: str) -> Dict[str, any]:
        lower = text.lower()
        if any(w in lower for w in ["remind", "alarm", "timer", "pomodoro"]):
            intent = "set_reminder"
        elif any(w in lower for w in ["open", "launch"]):
            intent = "open_app"
        elif any(w in lower for w in ["call", "dial", "phone"]):
            intent = "make_call"
        elif any(w in lower for w in ["summarize", "summary", "notes"]):
            intent = "summarize"
        elif any(w in lower for w in ["explain", "eli5", "what is", "how does"]):
            intent = "explain"
        elif any(w in lower for w in ["write", "email", "essay", "draft"]):
            intent = "write"
        elif any(w in lower for w in ["code", "python", "debug", "function", "kotlin"]):
            intent = "code"
        elif any(w in lower for w in ["translate", "anuvad"]):
            intent = "translate"
        elif any(w in lower for w in ["study", "plan", "timetable", "schedule"]):
            intent = "study_planner"
        elif any(w in lower for w in ["solve", "math", "equation", "derivative"]):
            intent = "math_solver"
        elif any(w in lower for w in ["torch", "flashlight", "volume", "battery"]):
            intent = "device_control"
        else:
            intent = "chat"

        return {
            "intent": intent,
            "confidence": 0.88,
            "probabilities": {intent: 0.88},
            "model_type": "heuristic_fallback",
        }
