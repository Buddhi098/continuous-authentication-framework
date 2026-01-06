import tensorflow as tf
import numpy as np
import os
import shutil
from pathlib import Path

print(f"TensorFlow Version: {tf.__version__}")

# -------------------------------------------------------------------------
# Project Paths
# -------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent

ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
ASSETS_DIR.mkdir(parents=True, exist_ok=True)

EXPORT_PATH = PROJECT_ROOT / "python" / "saved_autoencoder"
TFLITE_FILE_PATH = ASSETS_DIR / "model.tflite"

# -------------------------------------------------------------------------
# Model Hyperparameters
# -------------------------------------------------------------------------
INPUT_DIM = 110
LATENT_DIM = 16
BATCH_SIZE = 32

# -------------------------------------------------------------------------
# 1. Optimized Autoencoder for Anomaly Detection
# -------------------------------------------------------------------------
class SensorAutoencoder(tf.keras.Model):
    def __init__(self, input_dim=INPUT_DIM, latent_dim=LATENT_DIM):
        super().__init__()
        self.input_dim = input_dim
        self.latent_dim = latent_dim

        # ---------------- Encoder ----------------
        self.encoder = tf.keras.Sequential([
            tf.keras.layers.InputLayer(input_shape=(input_dim,)),

            tf.keras.layers.Dense(128),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.LeakyReLU(),

            tf.keras.layers.Dense(64),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.LeakyReLU(),

            tf.keras.layers.Dense(32),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.LeakyReLU(),

            tf.keras.layers.Dense(latent_dim, name="bottleneck")
        ], name="encoder")

        # ---------------- Decoder ----------------
        self.decoder = tf.keras.Sequential([
            tf.keras.layers.InputLayer(input_shape=(latent_dim,)),

            tf.keras.layers.Dense(32),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.LeakyReLU(),

            tf.keras.layers.Dense(64),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.LeakyReLU(),

            tf.keras.layers.Dense(128),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.LeakyReLU(),

            tf.keras.layers.Dense(input_dim, name="reconstruction")
        ], name="decoder")


        # ---------------- Optimizer ----------------
        lr_schedule = tf.keras.optimizers.schedules.CosineDecay(
            initial_learning_rate=1e-3,
            decay_steps=4000,
            alpha=0.05
        )

        self.optimizer = tf.keras.optimizers.AdamW(
            learning_rate=lr_schedule,
            weight_decay=1e-5
        )

        self.baked_weights = []

    # ------------------------------------------------------------------
    def call(self, inputs, training=False):
        z = self.encoder(inputs, training=training)
        return self.decoder(z, training=training)

    # ------------------------------------------------------------------
    def bake_weights(self):
        """Store factory default weights"""
        self.baked_weights = [
            tf.constant(w.numpy(), dtype=w.dtype)
            for w in self.trainable_variables
        ]

    # ------------------------------------------------------------------
    # SIGNATURE FUNCTIONS (KEEPED)
    # ------------------------------------------------------------------

    @tf.function(
        input_signature=[
            tf.TensorSpec(shape=[None, INPUT_DIM], dtype=tf.float32, name="inputs")
        ]
    )
    def infer_func(self, inputs):
        reconstruction = self.call(inputs, training=False)
        reconstruction_error = tf.reduce_mean(
            tf.square(inputs - reconstruction), axis=1
        )
        return {
            "reconstruction": reconstruction,
            "reconstruction_error": reconstruction_error
        }
    

    @tf.function(
        input_signature=[
            tf.TensorSpec(shape=[BATCH_SIZE, INPUT_DIM], dtype=tf.float32, name="inputs")
        ]
    )
    def train_func(self, inputs):
        with tf.GradientTape() as tape:
            reconstruction = self.call(inputs, training=True)
            loss = tf.reduce_mean(tf.square(inputs - reconstruction))  # MSE only

        grads = tape.gradient(loss, self.trainable_variables)
        self.optimizer.apply_gradients(zip(grads, self.trainable_variables))
        return {"loss": loss}
    

    @tf.function(
        input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")]
    )
    def init_model(self, x):
        """Reset model to factory baked weights"""
        if self.baked_weights:
            for var, baked in zip(self.trainable_variables, self.baked_weights):
                var.assign(baked)

        if hasattr(self.optimizer, "variables"):
            for v in self.optimizer.variables:
                v.assign(tf.zeros_like(v))

        return {"status": tf.constant(1.0, dtype=tf.float32)}

    # ------------------------------------------------------------------
    # Save / Restore weights (TFLite friendly)
    # ------------------------------------------------------------------
    def save_weights(self, x):
        return {f"val_{i}": v for i, v in enumerate(self.trainable_variables)}

    def restore_weights(self, **kwargs):
        for i, var in enumerate(self.trainable_variables):
            key = f"val_{i}"
            if key in kwargs:
                var.assign(kwargs[key])

        if hasattr(self.optimizer, "variables"):
            for v in self.optimizer.variables:
                v.assign(tf.zeros_like(v))

        return {"status": tf.constant(1.0, dtype=tf.float32)}

# -------------------------------------------------------------------------
# 2. Save + Convert to TFLite
# -------------------------------------------------------------------------
def main():

    if EXPORT_PATH.exists():
        shutil.rmtree(EXPORT_PATH)
    if TFLITE_FILE_PATH.exists():
        TFLITE_FILE_PATH.unlink()

    model = SensorAutoencoder()
    model(tf.zeros([1, INPUT_DIM]))  # build model
    model.bake_weights()

    save_fn = tf.function(model.save_weights).get_concrete_function(
        tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")
    )

    restore_specs = {
        f"val_{i}": tf.TensorSpec(shape=v.shape, dtype=v.dtype, name=f"val_{i}")
        for i, v in enumerate(model.trainable_variables)
    }

    restore_fn = tf.function(model.restore_weights).get_concrete_function(**restore_specs)

    tf.saved_model.save(
        model,
        EXPORT_PATH,
        signatures={
            "train": model.train_func,
            "infer": model.infer_func,
            "init_model": model.init_model,
            "save": save_fn,
            "restore": restore_fn
        }
    )

    print("SavedModel exported.")

    converter = tf.lite.TFLiteConverter.from_saved_model(
        str(EXPORT_PATH),
        signature_keys=["train", "infer", "init_model", "save", "restore"]
    )

    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]

    converter.experimental_enable_resource_variables = True

    tflite_model = converter.convert()
    with open(TFLITE_FILE_PATH, "wb") as f:
        f.write(tflite_model)

    print(f"TFLite model saved at: {TFLITE_FILE_PATH}")

# -------------------------------------------------------------------------
if __name__ == "__main__":
    main()
