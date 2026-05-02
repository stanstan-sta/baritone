#!/usr/bin/env python3
"""OpenRouter-compatible helper for turning instructions into Baritone commands.

This is intentionally a thin bridge: Baritone remains a Minecraft mod controlled
through chat commands, while this script speaks OpenRouter's OpenAI-compatible
Chat Completions API and emits the command(s) an external launcher should send.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
DEFAULT_MODEL = "openrouter/auto"

BARITONE_COMMAND_TOOL = {
    "type": "function",
    "function": {
        "name": "execute_baritone_command",
        "description": "Queue exactly one Baritone chat command to execute in Minecraft.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "A Baritone command beginning with #, for example #task sleep.",
                },
                "reason": {
                    "type": "string",
                    "description": "Brief reason this command is the next safe action.",
                },
            },
            "required": ["command"],
            "additionalProperties": False,
        },
    },
}

SYSTEM_PROMPT = """You control Baritone through chat commands.
Use exactly one execute_baritone_command tool call for the next action.
Prefer task-plan commands because they produce machine-readable status:
#task status
#task cancel
#task sleep
#task interact <x> <y> <z>
#task interact <block>
#task chest <x> <y> <z> withdraw|deposit <item> [count|all]
#task smelt <item> [count|all] [furnace|blast_furnace|smoker]
#task enqueue <block|x y z>
#task queue
#task clear
Never invent non-Baritone commands. If status indicates a task is running, use #task status unless the user asks to cancel or queue work."""


def request_openrouter(prompt: str, status: str, model: str) -> dict:
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        raise SystemExit("OPENROUTER_API_KEY is required")

    base_url = os.environ.get("OPENROUTER_BASE_URL", OPENROUTER_BASE_URL).rstrip("/")
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_message(prompt, status)},
        ],
        "tools": [BARITONE_COMMAND_TOOL],
        "tool_choice": {"type": "function", "function": {"name": "execute_baritone_command"}},
    }

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    referer = os.environ.get("OPENROUTER_HTTP_REFERER")
    title = os.environ.get("OPENROUTER_APP_TITLE", "Baritone Task Bridge")
    if referer:
        headers["HTTP-Referer"] = referer
    if title:
        headers["X-OpenRouter-Title"] = title

    request = urllib.request.Request(
        f"{base_url}/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        details = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"OpenRouter request failed: HTTP {exc.code}\n{details}") from exc


def build_user_message(prompt: str, status: str) -> str:
    if not status:
        return prompt
    return f"Current Baritone status:\n{status}\n\nUser instruction:\n{prompt}"


def extract_commands(response: dict) -> list[str]:
    choices = response.get("choices") or []
    if not choices:
        raise SystemExit("OpenRouter response did not contain any choices")

    message = choices[0].get("message") or {}
    commands: list[str] = []
    for tool_call in message.get("tool_calls") or []:
        function = tool_call.get("function") or {}
        if function.get("name") != "execute_baritone_command":
            continue
        raw_arguments = function.get("arguments") or "{}"
        try:
            arguments = json.loads(raw_arguments)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"Invalid tool arguments from model: {raw_arguments}") from exc
        command = arguments.get("command")
        if isinstance(command, str) and command.startswith("#"):
            commands.append(command)

    if commands:
        return commands

    content = message.get("content")
    if isinstance(content, str) and content.strip():
        return [content.strip()]
    raise SystemExit("OpenRouter response did not include a Baritone command")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Use OpenRouter to choose the next Baritone task command."
    )
    parser.add_argument("prompt", help="User instruction to convert into a Baritone command")
    parser.add_argument(
        "--status",
        default="",
        help="Latest #task status output, if available",
    )
    parser.add_argument(
        "--model",
        default=os.environ.get("OPENROUTER_MODEL", DEFAULT_MODEL),
        help=f"OpenRouter model id (default: {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the OpenRouter request body instead of calling the API",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.dry_run:
        print(json.dumps({
            "model": args.model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": build_user_message(args.prompt, args.status)},
            ],
            "tools": [BARITONE_COMMAND_TOOL],
            "tool_choice": {"type": "function", "function": {"name": "execute_baritone_command"}},
        }, indent=2))
        return 0

    response = request_openrouter(args.prompt, args.status, args.model)
    for command in extract_commands(response):
        print(command)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
