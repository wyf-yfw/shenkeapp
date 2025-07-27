import time
from io import BytesIO

from flask import Flask, request, jsonify, Response
from flask_cors import CORS
import json
from openai import OpenAI
# Use a pipeline as a high-level helper
from transformers import pipeline
from transformers import AutoTokenizer, AutoModelForCausalLM
import torch
from huggingface_hub import login
from transformers import pipeline, AutoTokenizer, TextIteratorStreamer
from threading import Thread
from openai import OpenAI
# Use a pipeline as a high-level helper
from transformers import pipeline
from transformers import AutoTokenizer, AutoModelForCausalLM
import torch
from huggingface_hub import login
from transformers import pipeline, AutoTokenizer, TextIteratorStreamer
from threading import Thread
from diffusers import DiffusionPipeline
import translate
from diffusers import AutoencoderKLWan, WanPipeline
from diffusers.utils import export_to_video
from transformers import BitsAndBytesConfig, AutoModelForCausalLM, pipeline
from diffusers import AutoPipelineForText2Image
import base64

app = Flask(__name__)
CORS(app)  # 允许跨域请求
stop_flags = {}  # key: session_id or ip, value: bool
active_requests = {}  # 记录每个 IP 当前是否有推理在进行
last_request_time = {}  # 请求频率控制（可选）
REQUEST_INTERVAL = 1.5  # 限制每个 IP 最少间隔 N 秒发起请求
@app.route("/chat", methods=["POST"])
def chat():
    data = request.get_json()
    user_input = data.get("message", "")
    quantization = data.get("quantization", None)  # 可选: "fp16", "int8", "int4"
    user_ip = request.remote_addr
    print(user_ip)
    # 防止重复请求（频率控制）
    now = time.time()
    if user_ip in last_request_time and now - last_request_time[user_ip] < REQUEST_INTERVAL:
        return jsonify({"error": "请求过于频繁，请稍后再试"}), 429
    last_request_time[user_ip] = now

    # 防止重复推理
    if active_requests.get(user_ip, False):
        return jsonify({"error": "已有请求正在进行，请先取消或等待其完成"}), 409

    stop_flags[user_ip] = False  # 每次新请求重置标志
    pipe = None
    print(model_name)
    print(model_type)
    print(quantization)
    # 投产后需要用到真实模型
    api = "sk-bcc06b8ab44f46f1aa8461e034497d6f"

    if model_name == "deepseek-chat":
        client = OpenAI(api_key=api, base_url="https://api.deepseek.com")

        response = client.chat.completions.create(
            model="deepseek-chat",
            messages=[
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": user_input},
            ],
            stream=False
        )
        model_reply = response.choices[0].message.content
        return model_reply

    elif model_name == "qwen3-1.7B":

        model_id = "Qwen/Qwen3-1.7B"

    elif model_name == "qwen3-0.6B":

        model_id = "data/model/qwen3-0.6b"

    elif model_name == "sdxl-turbo":

        model_id = "stabilityai/sdxl-turbo"
        pipe = AutoPipelineForText2Image.from_pretrained(
            "stabilityai/sdxl-turbo",
            torch_dtype=torch.float16,
            variant="fp16"
        )
    elif model_name == "Wan2.1-T2V-1.3B-Diffusers":

        model_id = "Wan2.1-T2V-1.3B-Diffusers"

        vae = AutoencoderKLWan.from_pretrained(model_id, subfolder="vae", torch_dtype=torch.float32)

        pipe = WanPipeline.from_pretrained(model_id, vae=vae, torch_dtype=torch.bfloat16)

    if model_type == "LLM":
        tokenizer = AutoTokenizer.from_pretrained(model_id)
        streamer = TextIteratorStreamer(tokenizer, skip_prompt=True, skip_special_tokens=True)

        quant_config = None
        #
        # if quantization == "int8":
        #     quant_config = BitsAndBytesConfig(load_in_8bit=True)
        # elif quantization == "int4":
        #     quant_config = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_compute_dtype=torch.float16)
        # elif quantization == "fp16":
        #     quant_config = BitsAndBytesConfig(torch_dtype=torch.float16)

        if quant_config:
            print(f"使用量化: {quantization}")
            model = AutoModelForCausalLM.from_pretrained(model_id, quantization_config=quant_config, device_map="auto")
        else:
            print("使用非量化模型")
            model = AutoModelForCausalLM.from_pretrained(model_id, device_map="auto")

        pipe = pipeline("text-generation", model=model, tokenizer=tokenizer)

        generation_kwargs = dict(
            text_inputs=user_input,
            streamer=streamer,
            max_new_tokens=100,
            do_sample=True,
            temperature=0.7,
            top_p=0.9
        )

        thread = Thread(target=pipe, kwargs=generation_kwargs)
        thread.start()

        def generate_stream():
            for new_text in streamer:
                if stop_flags.get(user_ip):
                    print("推理被中止")
                    break
                yield f"data: {new_text}\n\n"

        return Response(generate_stream(), mimetype="text/event-stream")
    elif model_type == "Text-to-Image":
        user_input = translate.translate(user_input)
        image = pipe(user_input, num_inference_steps=1, guidance_scale=0.0).images[0]
        if stop_flags.get(user_ip):
            print("推理被中止")
            return "推理中止", 400
        buffered = BytesIO()
        image.save(buffered, format="PNG")
        img_base64 = base64.b64encode(buffered.getvalue()).decode("utf-8")

        return jsonify({"image": img_base64})
    elif model_type == "Text-to-Video":
        output = pipe(
            prompt=user_input,
            negative_prompt=user_input,
            height=480,
            width=832,
            num_frames=81,
            guidance_scale=5.0
        ).frames[0]
        return output
    return "无效的请求或模型类型", 400


@app.route('/models')
def get_models():
    with open('data/models.json', 'r', encoding='utf-8') as f:
        data = json.load(f)
    return jsonify(data)


@app.route('/select_model', methods=['POST'])
def select_model():
    global model_name, model_type  # 声明使用全局变量

    data = request.get_json()
    model_name = data.get("model_name")
    # 加载 JSON 文件
    with open('data/models.json', 'r', encoding='utf-8') as f:
        models = json.load(f)
        matched_model = next((model for model in models if model["name"] == model_name), None)
    if matched_model:
        model_type = matched_model["type"]

        return {"status": "ok"}
    else:
        return {"status": "no"}

@app.route("/cancel", methods=["POST"])
def cancel():
    user_ip = request.remote_addr
    stop_flags[user_ip] = True
    return {"status": "cancelled"}

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
