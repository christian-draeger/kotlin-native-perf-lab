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
