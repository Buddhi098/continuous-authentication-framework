import tensorflow as tf
import numpy as np
import os
import shutil
from pathlib import Path

print(f"TensorFlow Version: {tf.__version__}")

# -------------------------------------------------------------------------
# Project Paths (IMPORTANT)
# -------------------------------------------------------------------------

# python/generate_model.py → authframework/
PROJECT_ROOT = Path(__file__).resolve().parent.parent

ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
ASSETS_DIR.mkdir(parents=True, exist_ok=True)

EXPORT_PATH = PROJECT_ROOT / "python" / "saved_autoencoder"
TFLITE_FILE_PATH = ASSETS_DIR / "model.tflite"

INPUT_DIM = 77
LATENT_DIM = 64
BATCH_SIZE = 32
LOSS_ALPHA = 0.9 # for combined loss function

# -------------------------------------------------------------------------
# 1. High-Accuracy Autoencoder Definition
# -------------------------------------------------------------------------
class SensorAutoencoder(tf.keras.Model):
    def __init__(self, input_dim=INPUT_DIM, latent_dim=LATENT_DIM):
        super(SensorAutoencoder, self).__init__()
        self.input_dim = input_dim
        self.latent_dim = latent_dim

        # --- Encoder ---
        self.encoder = tf.keras.Sequential([
            tf.keras.layers.InputLayer(input_shape=(input_dim,)),
            tf.keras.layers.Dense(256, activation=None),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.Activation('relu'),
            tf.keras.layers.Dense(128, activation=None),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.Activation('relu'),
            tf.keras.layers.Dense(latent_dim, activation=None, name="bottleneck"),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.Activation('relu')
        ], name="encoder")

        # --- Decoder ---
        self.decoder = tf.keras.Sequential([
            tf.keras.layers.InputLayer(input_shape=(latent_dim,)),
            tf.keras.layers.Dense(128, activation=None),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.Activation('relu'),
            tf.keras.layers.Dense(256, activation=None),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.Activation('relu'),
            tf.keras.layers.Dense(input_dim, activation='sigmoid', name="dec_output")
        ], name="decoder")

        # Optimizer with learning rate schedule
        lr_schedule = tf.keras.optimizers.schedules.CosineDecay(
            initial_learning_rate=0.0005,
            decay_steps=3000,
            alpha=0.1
        )

        self.optimizer = tf.keras.optimizers.Adam(learning_rate=lr_schedule)

        # Storage for baked weights
        self.baked_weights = []

    def call(self, inputs):
        encoded = self.encoder(inputs)
        decoded = self.decoder(encoded)
        return decoded

    def bake_weights(self):
        """Snapshots the current weights as Factory Defaults."""
        self.baked_weights = [tf.constant(var.numpy(), dtype=var.dtype) for var in self.trainable_variables]

    # -------------------------------------------------------------------------
    # Custom Signatures
    # -------------------------------------------------------------------------
    @tf.function(input_signature=[tf.TensorSpec(shape=[None, INPUT_DIM], dtype=tf.float32, name="inputs")])
    def infer_func(self, inputs):
        reconstruction = self.call(inputs)
        # Compute MSE reconstruction error per sample
        reconstruction_error = tf.reduce_mean(tf.square(inputs - reconstruction), axis=1)  # shape: [batch_size]
        return {
            "reconstruction": reconstruction,
            "reconstruction_error": reconstruction_error
        }

    @tf.function(input_signature=[tf.TensorSpec(shape=[BATCH_SIZE, INPUT_DIM], dtype=tf.float32, name="inputs")])
    def train_func(self, inputs):
        with tf.GradientTape() as tape:
            predictions = self.call(inputs)
            # Combined loss: MSE + Cosine Similarity
            mse_loss = tf.reduce_mean(tf.square(inputs - predictions))
            cos_loss = 1 - tf.reduce_mean(
                tf.reduce_sum(tf.nn.l2_normalize(inputs, axis=1) * tf.nn.l2_normalize(predictions, axis=1), axis=1)
            )
            loss = LOSS_ALPHA * mse_loss + (1 - LOSS_ALPHA) * cos_loss  # Weighted sum
        gradients = tape.gradient(loss, self.trainable_variables)
        self.optimizer.apply_gradients(zip(gradients, self.trainable_variables))
        return {"loss": loss}

    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")])
    def init_model(self, x):
        """Factory Reset to baked weights."""
        if len(self.baked_weights) > 0:
            for var, baked_val in zip(self.trainable_variables, self.baked_weights):
                var.assign(baked_val)
        if hasattr(self.optimizer, 'variables'):
            for var in self.optimizer.variables:
                var.assign(tf.zeros(shape=var.shape, dtype=var.dtype))
        return {"status": tf.constant(1.0, dtype=tf.float32)}

    # --- Save / Restore ---
    def save_weights(self, x):
        outputs = {f"val_{i}": var for i, var in enumerate(self.trainable_variables)}
        return outputs

    def restore_weights(self, **kwargs):
        for i, var in enumerate(self.trainable_variables):
            key = f"val_{i}"
            if key in kwargs:
                var.assign(kwargs[key])
        if hasattr(self.optimizer, 'variables'):
            for var in self.optimizer.variables:
                var.assign(tf.zeros(shape=var.shape, dtype=var.dtype))
        return {"status": tf.constant(1.0, dtype=tf.float32)}


# -------------------------------------------------------------------------
# 2. Main Pipeline for Saving + TFLite Conversion
# -------------------------------------------------------------------------
def main():

    # Remove old models
    if EXPORT_PATH.exists():
        shutil.rmtree(EXPORT_PATH)
    if TFLITE_FILE_PATH.exists():
        TFLITE_FILE_PATH.unlink()

    autoencoder = SensorAutoencoder(input_dim=INPUT_DIM, latent_dim=LATENT_DIM)
    autoencoder.bake_weights()

    # Generate concrete functions
    save_concrete_func = tf.function(autoencoder.save_weights).get_concrete_function(
        tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")
    )

    restore_input_specs = {f"val_{i}": tf.TensorSpec(shape=var.shape, dtype=var.dtype, name=f"val_{i}")
                           for i, var in enumerate(autoencoder.trainable_variables)}

    restore_concrete_func = tf.function(autoencoder.restore_weights).get_concrete_function(**restore_input_specs)

    # Save SavedModel
    if os.path.exists(EXPORT_PATH):
        shutil.rmtree(EXPORT_PATH)

    tf.saved_model.save(
        autoencoder,
        EXPORT_PATH,
        signatures={
            'train': autoencoder.train_func,
            'infer': autoencoder.infer_func,
            'init_model': autoencoder.init_model,
            'save': save_concrete_func,
            'restore': restore_concrete_func
        }
    )
    print("SavedModel created successfully.")

    # Convert to TFLite
    converter = tf.lite.TFLiteConverter.from_saved_model(
        str(EXPORT_PATH),
        signature_keys=['train', 'infer', 'init_model', 'save', 'restore']
    )
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]

    converter.experimental_enable_resource_variables = True

    try:
        tflite_model = converter.convert()
        with open(TFLITE_FILE_PATH, "wb") as f:
            f.write(tflite_model)
        print(f"Success! Model saved to: {TFLITE_FILE_PATH}")
    except Exception as e:
        print(f"Conversion failed: {e}")


if __name__ == "__main__":
    main()
