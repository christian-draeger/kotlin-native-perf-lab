# Embedding inference experiment

This preflight supplies a verified CPU reference for the optional inference pilot in [#155](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/155). It runs the Apache 2.0 `all-MiniLM-L6-v2` model with pinned ONNX and tokenizer bytes. The check validates FP32 output, shape, normalization, the attention mask across padded inputs and a simple similarity ordering. [Model and pooling contract](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/1110a243fdf4706b3f48f1d95db1a4f5529b4d41)

From the repository root with Python 3.14, install the experiment dependencies in a virtual environment and pass a directory for the verified weights.

```sh
experiment=components/ktserve/experiments/inference
python3 -m venv /tmp/ktserve-inference-venv
/tmp/ktserve-inference-venv/bin/python -m pip install -r "$experiment/requirements.txt"
/tmp/ktserve-inference-venv/bin/python "$experiment/verify_cpu.py" /tmp/ktserve-inference-model
```

The JSON output records the model and tokenizer hashes, device, runtime and tensor shape. The first run downloads about 91 MB. The check has no HTTP load phase. The separate inference evaluation will compare the same model and bounded request contract on CPU and GPU with a separate load generator.

## Run the HTTP example

Use JDK 21 for the ktServe JVM frontend. Run the loopback CPU reference service in one terminal and the frontend in another, from the repository root.

```sh
/tmp/ktserve-inference-venv/bin/python components/ktserve/experiments/inference/serve_cpu.py /tmp/ktserve-inference-model
```

```sh
components/ktserve/gradlew -p components/ktserve/experiments/inference run
```

```sh
curl --fail-with-body -H 'Content-Type: text/plain' --data-binary 'A persistent HTTP connection reuses a socket.' http://127.0.0.1:8080/embed
```

`POST /embed` accepts 1–1024 bytes of nonblank UTF-8 text and returns the model identifier and a 384-component unit vector. ktServe admits up to four requests, returns HTTP 503 when all four slots are in use, and returns HTTP 504 after two seconds waiting for inference. A client disconnect cancels the handler, aborts the upstream HTTP exchange and releases the slot. The CPU reference can finish an ONNX computation already in progress after its client disconnects. The two listeners bind to loopback. The serial Python HTTP service provides a correctness fixture for the frontend and model contract.

The smoke test checks the exact real-model response through ktServe. A separate stalled socket checks overload, the deadline, rejection of oversized upstream responses and cancellation of the upstream connection. [JDK HTTP cancellation contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html#sendAsync(java.net.http.HttpRequest,java.net.http.HttpResponse.BodyHandler,java.net.http.HttpResponse.PushPromiseHandler))

```sh
experiment=components/ktserve/experiments/inference
components/ktserve/gradlew -p "$experiment" installDist
/tmp/ktserve-inference-venv/bin/python "$experiment/smoke_http.py" \
  "$experiment/build/install/ktserve-inference-example/bin/ktserve-inference-example" /tmp/ktserve-inference-model
```
