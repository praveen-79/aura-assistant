"""
FastAPI Server & Live WebSocket Bridge for Aura Android AI Assistant.
Allows you to test and run Aura from your PC or Android phone browser (PWA),
or connect the native Android APK shell to this Python brain.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import socket
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from google import genai
from google.genai import types
from pydantic import BaseModel

from android_automation import AndroidAutomationEngine
from contact_manager import ContactManager, clean_text_for_tts
from gemini_agent import AuraGeminiAgent, get_live_api_tools
from memory_manager import MemoryManager
from workflow_manager import WorkflowManager

load_dotenv()

BASE_DIR = Path(__file__).parent
STATIC_DIR = BASE_DIR / "static"
STATIC_DIR.mkdir(exist_ok=True)
TTS_CACHE_DIR = BASE_DIR / "tts_cache"
TTS_CACHE_DIR.mkdir(exist_ok=True)

contact_manager = ContactManager(BASE_DIR / "contacts.json")
automation_engine = AndroidAutomationEngine(contact_manager)
workflow_manager = WorkflowManager(automation_engine, BASE_DIR / "workflows.json")
memory_manager = MemoryManager(BASE_DIR / "owner_memory.json")
agent = AuraGeminiAgent(
    automation_engine,
    workflow_manager=workflow_manager,
    memory_manager=memory_manager,
)

app = FastAPI(title="Aura - Multilingual Android AI Assistant")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


def get_local_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class ChatRequest(BaseModel):
    text: str
    language_hint: Optional[str] = "auto"


class SettingsRequest(BaseModel):
    api_key: Optional[str] = None
    preferred_language: Optional[str] = None


class ContactRequest(BaseModel):
    name: str
    phone: str
    aliases: Optional[List[str]] = None
    relationship: Optional[str] = ""


class WorkflowRequest(BaseModel):
    name: str
    triggers: List[str]
    steps: List[Dict[str, Any]]
    reply_en: Optional[str] = ""
    reply_hi: Optional[str] = ""


@app.get("/")
async def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/download.html")
@app.get("/download")
async def download_page():
    return FileResponse(STATIC_DIR / "download.html")


@app.get("/download/AuraAssistant.apk")
async def download_apk():
    apk_candidates = [
        BASE_DIR / "AuraAssistant.apk",
        BASE_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
    ]
    for candidate in apk_candidates:
        if candidate.exists():
            return FileResponse(
                path=str(candidate),
                filename="AuraAssistant.apk",
                media_type="application/vnd.android.package-archive",
            )
    return JSONResponse(
        {"error": "AuraAssistant.apk is currently building. Please try again in a few seconds."},
        status_code=404,
    )



@app.get("/api/status")
async def get_status():
    adb_status = automation_engine.check_adb_connection()
    lan_ip = get_local_lan_ip()
    return {
        "status": "ok",
        "has_api_key": bool(agent.api_key),
        "preferred_language": agent.preferred_language,
        "lan_ip": lan_ip,
        "mobile_url": f"http://{lan_ip}:8000",
        "models": {
            "live_voice": "gemini-3.8-live",
            "fast_free_tier": "gemini-3.8-flash",
        },
        "adb": adb_status,
        "contacts": [c.to_dict() for c in contact_manager.contacts],
        "workflows": workflow_manager.list_workflows(),
        "memories": memory_manager.list_memories(),
        "recent_actions": automation_engine.recent_actions[:10],
    }


@app.post("/api/settings")
async def update_settings(req: SettingsRequest):
    if req.api_key is not None:
        agent.set_api_key(req.api_key)
        try:
            env_file = BASE_DIR / ".env"
            env_file.write_text(f"GEMINI_API_KEY={req.api_key.strip()}\n", encoding="utf-8")
        except Exception:
            pass
    if req.preferred_language is not None:
        agent.preferred_language = req.preferred_language
    return {"status": "ok", "has_api_key": bool(agent.api_key), "preferred_language": agent.preferred_language}


@app.post("/api/chat")
async def chat_endpoint(req: ChatRequest):
    result = agent.chat_with_tools(req.text, language_hint=req.language_hint)
    result["recent_actions"] = automation_engine.recent_actions[:8]
    return JSONResponse(result)


@app.get("/api/tts")
async def tts_endpoint(text: str, lang: str = "te"):
    """
    Synthesizes native multilingual speech audio (Telugu 'te', Tamil 'ta', Hindi 'hi',
    Bengali 'bn', Marathi 'mr', Spanish 'es', English 'en') and caches MP3 files locally
    in `tts_cache/` so subsequent or offline replays work even on Windows PCs without
    an installed OS Telugu/Tamil SAPI5 voice pack.
    """
    spoken_text = clean_text_for_tts(text)[:200]
    tl = lang.split("-")[0].lower() if lang else "te"
    if tl not in {"te", "ta", "hi", "bn", "mr", "es", "en", "kn", "ml"}:
        tl = "en"

    cache_key = hashlib.md5(f"{tl}:{spoken_text}".encode("utf-8")).hexdigest()
    cache_path = TTS_CACHE_DIR / f"{cache_key}.mp3"

    if cache_path.exists() and cache_path.stat().st_size > 100:
        return Response(content=cache_path.read_bytes(), media_type="audio/mpeg")

    try:
        async with httpx.AsyncClient(timeout=4.5) as client:
            resp = await client.get(
                "https://translate.googleapis.com/translate_tts",
                params={"ie": "UTF-8", "client": "gtx", "tl": tl, "q": spoken_text},
                headers={"User-Agent": "Mozilla/5.0"},
            )
            if resp.status_code == 200 and len(resp.content) > 100:
                cache_path.write_bytes(resp.content)
                return Response(content=resp.content, media_type="audio/mpeg")
    except Exception:
        pass

    return JSONResponse({"status": "fallback_required", "lang": tl, "text": spoken_text}, status_code=404)



@app.post("/api/contacts")
async def add_contact_endpoint(req: ContactRequest):
    added = contact_manager.add_contact(
        name=req.name,
        phone=req.phone,
        aliases=req.aliases,
        relationship=req.relationship or "",
    )
    return {"status": "ok", "contact": added.to_dict(), "contacts": [c.to_dict() for c in contact_manager.contacts]}


class BulkContactsRequest(BaseModel):
    contacts: List[Dict[str, str]]


@app.post("/api/contacts/sync-bulk")
async def sync_contacts_bulk(req: BulkContactsRequest):
    res = contact_manager.sync_bulk_contacts(req.contacts)
    res["contacts"] = [c.to_dict() for c in contact_manager.contacts]
    return res


@app.post("/api/contacts/sync-adb")
async def sync_contacts_adb():
    res = contact_manager.sync_from_android_adb()
    res["contacts"] = [c.to_dict() for c in contact_manager.contacts]
    return res


@app.get("/api/workflows")
async def get_workflows():
    return {"status": "ok", "workflows": workflow_manager.list_workflows()}


@app.post("/api/workflows")
async def create_workflow(req: WorkflowRequest):
    wf = workflow_manager.add_workflow(
        name=req.name,
        triggers=req.triggers,
        steps=req.steps,
        reply_en=req.reply_en or "",
        reply_hi=req.reply_hi or "",
    )
    return {"status": "ok", "workflow": wf, "workflows": workflow_manager.list_workflows()}


@app.websocket("/ws/live")
async def gemini_live_websocket(ws: WebSocket):
    """
    Real-time bidirectional voice streaming bridge to `gemini-3.8-live`.
    """
    await ws.accept()
    if not agent.api_key:
        await ws.send_json({
            "type": "error",
            "message": "Please set your Gemini API Key in Settings first to use Gemini 3.8 Live audio streaming.",
        })
        await ws.close()
        return

    client = genai.Client(api_key=agent.api_key)
    config = types.LiveConnectConfig(
        response_modalities=[types.Modality.AUDIO],
        system_instruction=types.Content(
            parts=[types.Part(text=agent._build_system_instruction())]
        ),
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
        tools=get_live_api_tools(),
    )

    try:
        async with client.aio.live.connect(model="gemini-3.8-live", config=config) as session:
            await ws.send_json({"type": "connected", "model": "gemini-3.8-live"})

            async def receive_from_client():
                try:
                    while True:
                        raw = await ws.receive_text()
                        msg = json.loads(raw)
                        msg_type = msg.get("type")
                        if msg_type == "audio":
                            pcm_bytes = base64.b64decode(msg["data"])
                            await session.send_realtime_input(
                                audio=types.Blob(data=pcm_bytes, mime_type="audio/pcm;rate=16000")
                            )
                        elif msg_type == "audio_stream_end":
                            await session.send_realtime_input(audio_stream_end=True)
                        elif msg_type == "text":
                            await session.send_realtime_input(text=msg["text"])
                except WebSocketDisconnect:
                    pass

            async def receive_from_gemini():
                async for response in session.receive():
                    content = response.server_content
                    if content:
                        if content.model_turn:
                            for part in content.model_turn.parts:
                                if part.inline_data and part.inline_data.data:
                                    b64_audio = base64.b64encode(part.inline_data.data).decode("ascii")
                                    await ws.send_json({
                                        "type": "audio",
                                        "data": b64_audio,
                                        "sample_rate": 24000,
                                    })
                        if content.input_transcription and content.input_transcription.text:
                            await ws.send_json({
                                "type": "input_transcript",
                                "text": content.input_transcription.text,
                            })
                        if content.output_transcription and content.output_transcription.text:
                            await ws.send_json({
                                "type": "output_transcript",
                                "text": content.output_transcription.text,
                            })
                        if content.interrupted is True:
                            await ws.send_json({"type": "interrupted"})
                        if content.turn_complete is True:
                            await ws.send_json({"type": "turn_complete"})

                    if response.tool_call and response.tool_call.function_calls:
                        fn_responses = []
                        for call in response.tool_call.function_calls:
                            args = call.args if isinstance(call.args, dict) else {}
                            result = agent.execute_tool(call.name, args)
                            await ws.send_json({
                                "type": "tool_executed",
                                "name": call.name,
                                "args": args,
                                "result": result,
                            })
                            fn_responses.append(
                                types.FunctionResponse(
                                    id=call.id,
                                    name=call.name,
                                    response={"result": result},
                                )
                            )
                        await session.send_tool_response(function_responses=fn_responses)

            await asyncio.gather(receive_from_client(), receive_from_gemini())
    except Exception as exc:
        try:
            await ws.send_json({"type": "error", "message": str(exc)})
        except Exception:
            pass


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)
