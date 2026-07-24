# Provider Hub

The application supports three transport families:

- OpenAI-compatible Chat Completions and function tools;
- Anthropic Messages and tool use;
- Gemini generateContent and function declarations.

Built-in presets cover OpenCode Go, OpenAI, Anthropic, Gemini, OpenRouter, Groq, Cerebras, DeepSeek, Mistral, xAI, Moonshot/Kimi, MiniMax, Z.AI, Together, Fireworks, NVIDIA NIM, GitHub Models, Cloudflare/Azure/Bedrock/Vertex gateways, Vercel AI Gateway, LiteLLM, Ollama, LM Studio, LocalAI and vLLM.

Presets with tenant-specific endpoints intentionally leave Base URL empty. Any compatible provider can be added through Custom Provider.

API keys are encrypted by Android Keystore. Provider metadata contains no clear-text API key. Cloud calls additionally require the project-scoped `network` permission.
