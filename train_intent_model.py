"""
AuraAssistant - Deep Learning Intent Classification Neural Network (PyTorch).
Trains an EmbeddingBag + Feedforward Deep Neural Network on user intents:
- chat (General conversation, Q&A)
- summarize (Summarize articles, text, notes)
- explain (Explain concepts, topics, ELI5)
- write (Essays, emails, blogs, messages)
- code (Coding, debugging, python, algorithms)
- translate (Language translation, Telugu, Hindi)
- study_planner (Study schedules, revision plans)
- math_solver (Step-by-step math problem solving)
- set_reminder (Reminders, alarms, timers)
- open_app (Open Instagram, YouTube, WhatsApp, Maps)
- make_call (Phone calls, contact dialing)
- device_control (Flashlight, volume, battery status)
"""

import os
import json
import re
from pathlib import Path
from typing import List, Tuple, Dict

# Intent labeled dataset
TRAINING_DATA = [
    # 1. Chat
    ("hello aura", "chat"),
    ("hi there", "chat"),
    ("hey aura how are you", "chat"),
    ("good morning", "chat"),
    ("who are you", "chat"),
    ("tell me about yourself", "chat"),
    ("namaste", "chat"),
    ("bagunnara", "chat"),
    ("kaise ho", "chat"),
    ("what can you do", "chat"),
    ("tell me a fun fact", "chat"),
    ("tell me a joke", "chat"),

    # 2. Summarize
    ("summarize this article for me", "summarize"),
    ("give me a short summary of this pdf", "summarize"),
    ("make notes from this text", "summarize"),
    ("summarize in bullet points", "summarize"),
    ("give me key takeaways from this chapter", "summarize"),
    ("tldr of this document", "summarize"),
    ("shorten this paragraph", "summarize"),
    ("condense this lecture into key points", "summarize"),

    # 3. Explain
    ("explain quantum computing in simple words", "explain"),
    ("explain like I'm 5 how black holes work", "explain"),
    ("what is the theory of relativity", "explain"),
    ("explain transformers in machine learning", "explain"),
    ("how does photosynthesis work", "explain"),
    ("explain how neural networks learn", "explain"),
    ("teach me about blockchain", "explain"),
    ("what is inflation and how does it happen", "explain"),

    # 4. Write
    ("write an email to my manager asking for leave", "write"),
    ("write a sick leave application", "write"),
    ("draft an essay on artificial intelligence", "write"),
    ("write a blog post about time management", "write"),
    ("compose a polite follow up email", "write"),
    ("write a poem about stars", "write"),
    ("draft a letter of recommendation", "write"),
    ("write an apology note to a friend", "write"),

    # 5. Code
    ("write a python function to reverse a linked list", "code"),
    ("debug this python error for me", "code"),
    ("how to do list comprehension in python", "code"),
    ("write a binary search algorithm in kotlin", "code"),
    ("fix this syntax error in javascript", "code"),
    ("explain this sql query", "code"),
    ("generate a rest api in fastapi", "code"),
    ("write a regex to match email addresses", "code"),

    # 6. Translate
    ("translate this text to telugu", "translate"),
    ("how do you say good morning in hindi", "translate"),
    ("translate hello into spanish", "translate"),
    ("translate this sentence to english", "translate"),
    ("telugu lo translate cheyi", "translate"),
    ("hindi me anuvad karo", "translate"),
    ("translate how are you to french", "translate"),

    # 7. Study Planner
    ("create a study plan for my exams next week", "study_planner"),
    ("plan a schedule to learn machine learning in 30 days", "study_planner"),
    ("make a timetable for engineering semester exams", "study_planner"),
    ("how should i prepare for gate exam", "study_planner"),
    ("give me a daily study routine for 4 hours", "study_planner"),
    ("study timetable create cheyi", "study_planner"),

    # 8. Math Solver
    ("solve this quadratic equation x^2 + 5x + 6 = 0", "math_solver"),
    ("calculate the derivative of sin(x)*cos(x)", "math_solver"),
    ("solve 2x + 3 = 11 step by step", "math_solver"),
    ("find the area of a circle with radius 7", "math_solver"),
    ("what is the integral of e^x", "math_solver"),
    ("solve this algebra problem", "math_solver"),

    # 9. Set Reminder / Alarm
    ("remind me to study at 7 PM", "set_reminder"),
    ("set a reminder to take medicine in 2 hours", "set_reminder"),
    ("set an alarm for 6 AM tomorrow", "set_reminder"),
    ("set a 20 minute timer for pomodoro", "set_reminder"),
    ("remind me to call mom at 8 pm", "set_reminder"),
    ("alarm pettu 7 am ki", "set_reminder"),
    ("kal subah 6 baje ka alarm lagao", "set_reminder"),

    # 10. Open App
    ("open instagram", "open_app"),
    ("launch whatsapp", "open_app"),
    ("open youtube and play music", "open_app"),
    ("open google maps", "open_app"),
    ("open spotify", "open_app"),
    ("instagram open cheyi", "open_app"),
    ("youtube kholo", "open_app"),
    ("open reels", "open_app"),

    # 11. Make Call
    ("call Rahul", "make_call"),
    ("make a phone call to Amma", "make_call"),
    ("dial Mom", "make_call"),
    ("phone call cheyi Nanna ki", "make_call"),
    ("Rahul ko call karo", "make_call"),
    ("call Papa", "make_call"),

    # 12. Device Control
    ("turn on flashlight", "device_control"),
    ("torch off cheyi", "device_control"),
    ("increase volume", "device_control"),
    ("mute phone", "device_control"),
    ("check my battery status", "device_control"),
    ("switch off torch", "device_control"),
]


def tokenize(text: str) -> List[str]:
    clean = re.sub(r"[^a-zA-Z0-9\s]", " ", text.lower())
    return [w for w in clean.split() if len(w) > 0]


def build_vocab(data: List[Tuple[str, str]]) -> Tuple[Dict[str, int], Dict[str, int]]:
    word_to_idx = {"<PAD>": 0, "<UNK>": 1}
    for text, _ in data:
        for word in tokenize(text):
            if word not in word_to_idx:
                word_to_idx[word] = len(word_to_idx)

    intents = sorted(list(set(intent for _, intent in data)))
    intent_to_idx = {intent: i for i, intent in enumerate(intents)}
    return word_to_idx, intent_to_idx


def train_and_save():
    import torch
    import torch.nn as nn
    import torch.optim as optim

    print("Building vocabulary and encoding labels...")
    word_to_idx, intent_to_idx = build_vocab(TRAINING_DATA)
    idx_to_intent = {v: k for k, v in intent_to_idx.items()}

    print(f"Total vocabulary size: {len(word_to_idx)}")
    print(f"Total intent classes ({len(intent_to_idx)}): {list(intent_to_idx.keys())}")

    # Prepare tensors (Bag-of-Words / EmbeddingBag representation)
    inputs = []
    offsets = [0]
    targets = []
    for text, intent in TRAINING_DATA:
        tokens = tokenize(text)
        token_indices = [word_to_idx.get(w, word_to_idx["<UNK>"]) for w in tokens]
        if not token_indices:
            token_indices = [word_to_idx["<UNK>"]]
        inputs.extend(token_indices)
        offsets.append(len(inputs))
        targets.append(intent_to_idx[intent])

    offsets = offsets[:-1]  # Exclude last boundary
    input_tensor = torch.tensor(inputs, dtype=torch.long)
    offsets_tensor = torch.tensor(offsets, dtype=torch.long)
    targets_tensor = torch.tensor(targets, dtype=torch.long)

    # Define Neural Network
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

    embed_dim = 64
    hidden_dim = 48
    num_classes = len(intent_to_idx)

    model = IntentClassifierNN(len(word_to_idx), embed_dim, hidden_dim, num_classes)
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=0.015, weight_decay=1e-4)

    print("\nTraining Neural Intent Classifier for 120 epochs...")
    model.train()
    for epoch in range(1, 121):
        optimizer.zero_grad()
        output = model(input_tensor, offsets_tensor)
        loss = criterion(output, targets_tensor)
        loss.backward()
        optimizer.step()

        if epoch % 20 == 0 or epoch == 120:
            preds = output.argmax(dim=1)
            acc = (preds == targets_tensor).float().mean().item() * 100
            print(f"Epoch {epoch:3d}/120 | Loss: {loss.item():.4f} | Training Accuracy: {acc:.1f}%")

    # Evaluation on unseen test inputs
    model.eval()
    test_samples = [
        "explain to me how quantum physics operates",
        "write an email to professor asking for extension",
        "remind me at 9 pm to do homework",
        "open instagram reels right now",
        "solve 3x + 5 = 20",
        "make a 2 week study schedule for exams",
        "turn on torch light",
        "call my sister Priya",
        "translate welcome to telugu",
    ]

    print("\nEvaluating trained model on sample queries:")
    with torch.no_grad():
        for query in test_samples:
            toks = tokenize(query)
            idxs = [word_to_idx.get(w, word_to_idx["<UNK>"]) for w in toks]
            t_inp = torch.tensor(idxs, dtype=torch.long)
            t_off = torch.tensor([0], dtype=torch.long)
            logits = model(t_inp, t_off)
            probs = torch.softmax(logits, dim=1)
            conf, pred_idx = torch.max(probs, dim=1)
            intent = idx_to_intent[pred_idx.item()]
            print(f"Query: \"{query}\" -> Intent: {intent} (Confidence: {conf.item():.2%})")

    # Save model artifacts
    artifacts_dir = Path(__file__).parent / "model_artifacts"
    artifacts_dir.mkdir(exist_ok=True)

    torch.save(model.state_dict(), artifacts_dir / "intent_model.pt")
    with open(artifacts_dir / "vocab.json", "w", encoding="utf-8") as f:
        json.dump({
            "word_to_idx": word_to_idx,
            "intent_to_idx": intent_to_idx,
            "idx_to_intent": idx_to_intent,
            "embed_dim": embed_dim,
            "hidden_dim": hidden_dim,
            "num_classes": num_classes,
        }, f, indent=2)

    print(f"\n[SUCCESS] Model weights saved to {artifacts_dir / 'intent_model.pt'}")
    print(f"[SUCCESS] Vocab and metadata saved to {artifacts_dir / 'vocab.json'}")


if __name__ == "__main__":
    train_and_save()
