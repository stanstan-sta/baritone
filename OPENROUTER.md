# OpenRouter Compatibility

Baritone does not call LLM providers directly. It exposes deterministic chat
commands and task-plan status output, while an external bridge can decide which
command to send next.

This repo includes `scripts/openrouter_task_bridge.py`, a minimal OpenRouter
Chat Completions bridge that uses the OpenAI-compatible `/chat/completions`
format and tool calling.

## Environment

```sh
set OPENROUTER_API_KEY=sk-or-...
set OPENROUTER_MODEL=openrouter/auto
```

Optional attribution headers:

```sh
set OPENROUTER_HTTP_REFERER=https://your-app.example
set OPENROUTER_APP_TITLE=Baritone Task Bridge
```

## Usage

Ask for the next command:

```sh
python scripts/openrouter_task_bridge.py "Sleep in the nearest bed"
```

Example output:

```text
#task sleep
```

Pass the latest `#task status` output when continuing a running plan:

```sh
python scripts/openrouter_task_bridge.py "keep going" --status "Plan 'sleep' status=running"
```

Inspect the exact OpenRouter request without making a network call:

```sh
python scripts/openrouter_task_bridge.py "deposit all cobblestone in this chest" --dry-run
```

## Bridge Contract

The bridge asks the model to call one function:

```json
{
  "type": "function",
  "function": {
    "name": "execute_baritone_command",
    "parameters": {
      "type": "object",
      "properties": {
        "command": { "type": "string" },
        "reason": { "type": "string" }
      },
      "required": ["command"],
      "additionalProperties": false
    }
  }
}
```

An external launcher should send the emitted command into Minecraft chat, then
feed resulting status text back through `--status`.

OpenRouter documents Chat Completions as OpenAI-compatible and accepts bearer
API keys at `https://openrouter.ai/api/v1/chat/completions`.
