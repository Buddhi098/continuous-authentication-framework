import tensorflow as tf
import numpy as np
import os
import shutil
from pathlib import Path
from tensorflow.keras import regularizers

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
# Optimization Constants
# -------------------------------------------------------------------------
INPUT_DIM = 110
LATENT_DIM = 64
BATCH_SIZE = 32
DROPOUT_RATE = 0.2
L2_REG = 1e-5

# -------------------------------------------------------------------------
# 1. Optimized Autoencoder (Decomposed Layers to Fix Export Bug)
# -------------------------------------------------------------------------
class SensorAutoencoder(tf.keras.Model):
    def __init__(self, input_dim=INPUT_DIM, latent_dim=LATENT_DIM):
        super().__init__()
        self.input_dim = input_dim
        self.latent_dim = latent_dim
        
        reg = regularizers.l2(L2_REG)

        # ---------------- Encoder Layers ----------------
        # We define layers individually to avoid 'untracked resource' bugs 
        # with Dropout in Sequential models on TF 2.16+
        
        # Layer 1
        self.enc_dense1 = tf.keras.layers.Dense(256, kernel_regularizer=reg)
        self.enc_norm1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        # Layer 2
        self.enc_dense2 = tf.keras.layers.Dense(128, kernel_regularizer=reg)
        self.enc_norm2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        # Layer 3 (Bottleneck)
        self.enc_bottleneck = tf.keras.layers.Dense(latent_dim, name="bottleneck", kernel_regularizer=reg)

        # ---------------- Decoder Layers ----------------
        # Layer 1
        self.dec_dense1 = tf.keras.layers.Dense(128, kernel_regularizer=reg)
        self.dec_norm1 = tf.keras.layers.LayerNormalization()
        self.dec_act1 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        # Layer 2
        self.dec_dense2 = tf.keras.layers.Dense(256, kernel_regularizer=reg)
        self.dec_norm2 = tf.keras.layers.LayerNormalization()
        self.dec_act2 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        # Output Layer
        self.dec_output = tf.keras.layers.Dense(input_dim, name="reconstruction", activation="linear")

        # ---------------- Optimizer ----------------
        lr_schedule = tf.keras.optimizers.schedules.CosineDecay(
            initial_learning_rate=2e-3,
            decay_steps=5000,
            alpha=0.01
        )

        self.optimizer = tf.keras.optimizers.AdamW(
            learning_rate=lr_schedule,
            weight_decay=1e-4
        )

        self.baked_weights = []

    # ------------------------------------------------------------------
    # Core Logic (Using tf.nn.dropout instead of Layer objects)
    # ------------------------------------------------------------------
    def call(self, inputs, training=False):
        # --- ENCODER ---
        x = self.enc_dense1(inputs)
        x = self.enc_norm1(x)
        x = self.enc_act1(x)
        if training:
            x = tf.nn.dropout(x, rate=DROPOUT_RATE) # Stateless dropout

        x = self.enc_dense2(x)
        x = self.enc_norm2(x)
        x = self.enc_act2(x)
        if training:
            x = tf.nn.dropout(x, rate=DROPOUT_RATE)

        z = self.enc_bottleneck(x)

        # --- DECODER ---
        x = self.dec_dense1(z)
        x = self.dec_norm1(x)
        x = self.dec_act1(x)
        if training:
            x = tf.nn.dropout(x, rate=DROPOUT_RATE)

        x = self.dec_dense2(x)
        x = self.dec_norm2(x)
        x = self.dec_act2(x)
        if training:
            x = tf.nn.dropout(x, rate=DROPOUT_RATE)

        return self.dec_output(x)

    # ------------------------------------------------------------------
    # Helper: Bake Weights
    # ------------------------------------------------------------------
    def bake_weights(self):
        self.baked_weights = [
            tf.constant(w.numpy(), dtype=w.dtype)
            for w in self.trainable_variables
        ]

    # ------------------------------------------------------------------
    # SIGNATURE FUNCTIONS
    # ------------------------------------------------------------------
    @tf.function(
        input_signature=[
            tf.TensorSpec(shape=[None, INPUT_DIM], dtype=tf.float32, name="inputs")
        ]
    )
    def infer_func(self, inputs):
        # training=False -> tf.nn.dropout is skipped automatically by our boolean check
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
        # training=True -> tf.nn.dropout is active
        with tf.GradientTape() as tape:
            reconstruction = self.call(inputs, training=True)
            loss = tf.reduce_mean(tf.square(inputs - reconstruction))

        grads = tape.gradient(loss, self.trainable_variables)
        self.optimizer.apply_gradients(zip(grads, self.trainable_variables))
        return {"loss": loss}
    

    @tf.function(
        input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")]
    )
    def init_model(self, x):
        if self.baked_weights:
            for var, baked in zip(self.trainable_variables, self.baked_weights):
                var.assign(baked)
        
        # Safe optimizer reset
        if hasattr(self.optimizer, "variables"):
            for v in self.optimizer.variables:
                v.assign(tf.zeros_like(v))

        return {"status": tf.constant(1.0, dtype=tf.float32)}

    # ------------------------------------------------------------------
    # Save / Restore
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
# 2. Main Execution
# -------------------------------------------------------------------------
def main():
    if EXPORT_PATH.exists():
        shutil.rmtree(EXPORT_PATH)
    if TFLITE_FILE_PATH.exists():
        TFLITE_FILE_PATH.unlink()

    print("Initializing Optimized Model...")
    model = SensorAutoencoder()
    model(tf.zeros([1, INPUT_DIM])) # Build
    model.bake_weights()

    save_fn = tf.function(model.save_weights).get_concrete_function(
        tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")
    )

    restore_specs = {
        f"val_{i}": tf.TensorSpec(shape=v.shape, dtype=v.dtype, name=f"val_{i}")
        for i, v in enumerate(model.trainable_variables)
    }
    restore_fn = tf.function(model.restore_weights).get_concrete_function(**restore_specs)

    print(f"Exporting SavedModel to {EXPORT_PATH}...")
    
    # Export
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

    print("Converting to TFLite...")
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

    print(f"✅ SUCCESS: Optimized TFLite model saved at: {TFLITE_FILE_PATH}")

if __name__ == "__main__":
    main()