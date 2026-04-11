import tensorflow as tf
from typing import Dict, Any, List
from .base import BaseAnomalyDetector
from config import SEQUENCE_LENGTH, BATCH_SIZE, SENSOR_INPUT_DIM


class SensorAuthenticator(BaseAnomalyDetector):

    def __init__(
        self,
        input_dim: int,
        seq_len: int,
        batch_size: int = 32,
        learning_rate: float = 1e-3,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)

        self.seq_len = seq_len
        self.input_dim = input_dim

        # ==========================================================
        # SVDD CENTER (TFLite-safe trainable variable)
        # ==========================================================
        self.center = self.add_weight(
            name="svdd_center",
            shape=(32,),
            initializer="zeros",
            trainable=False
        )

        # ==========================================================
        # ENCODER
        # ==========================================================
        self.enc_conv1 = tf.keras.layers.Conv1D(32, 5, strides=2, padding='same')
        self.enc_ln1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.ReLU()

        self.enc_conv2 = tf.keras.layers.SeparableConv1D(64, 3, strides=2, padding='same')
        self.enc_ln2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.ReLU()

        self.enc_gap = tf.keras.layers.GlobalAveragePooling1D()
        self.latent_dense = tf.keras.layers.Dense(32)

        # ==========================================================
        # DECODER
        # ==========================================================
        self.dec_dense = tf.keras.layers.Dense((seq_len // 4) * 64, activation='relu')
        self.dec_reshape = tf.keras.layers.Reshape((seq_len // 4, 64))

        self.dec_up1 = tf.keras.layers.UpSampling1D(2)
        self.dec_conv1 = tf.keras.layers.SeparableConv1D(64, 3, padding='same', activation='relu')

        self.dec_up2 = tf.keras.layers.UpSampling1D(2)
        self.dec_conv2 = tf.keras.layers.SeparableConv1D(32, 3, padding='same', activation='relu')

        self.dec_out = tf.keras.layers.Conv1D(input_dim, 3, padding='same')

        self.optimizer = tf.keras.optimizers.Adam(learning_rate)
        self.loss_fn = tf.keras.losses.MeanSquaredError()

        self._build()
        self.bake_weights()

    # ==========================================================
    # REQUIRED BUILD STEP
    # ==========================================================
    def _build(self):
        """Forces the creation of layer and optimizer variables."""
        # 1. Forward pass to build layer weights
        x = tf.zeros((1, self.seq_len, self.input_dim))
        
        # 2. Dummy training step to force optimizer to build its slot variables
        with tf.GradientTape() as tape:
            latent = self._encode(x)
            recon = self._decode(latent)
            loss = tf.reduce_mean(tf.square(x - recon))
            
        grads = tape.gradient(loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))
        
        # 3. Reset center back to strict zeros (dummy step might shift it)
        self.center.assign(tf.zeros_like(self.center))

    # ==========================================================
    # ENCODER
    # ==========================================================
    def _encode(self, x):
        x = self.enc_conv1(x)
        x = self.enc_ln1(x)
        x = self.enc_act1(x)

        x = self.enc_conv2(x)
        x = self.enc_ln2(x)
        x = self.enc_act2(x)

        x = self.enc_gap(x)
        return self.latent_dense(x)

    # ==========================================================
    # DECODER
    # ==========================================================
    def _decode(self, z):
        x = self.dec_dense(z)
        x = self.dec_reshape(x)

        x = self.dec_up1(x)
        x = self.dec_conv1(x)

        x = self.dec_up2(x)
        x = self.dec_conv2(x)

        return self.dec_out(x)

    # ==========================================================
    # FORWARD PASS
    # ==========================================================
    def call(self, inputs, training=False):
        latent = self._encode(inputs)
        recon = self._decode(latent)
        return recon, latent

    # ==========================================================
    # REQUIRED PROPERTIES
    # ==========================================================
    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    @property
    def persistent_weights(self) -> List[tf.Variable]:
        """Gathers all weights required for export, including center and optimizer."""
        return self.trainable_weights + self.non_trainable_weights + self.optimizer.variables

    # ==========================================================
    # TRAIN STEP
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[BATCH_SIZE, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32)
    ])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        with tf.GradientTape() as tape:
            latent = self._encode(inputs)
            recon = self._decode(latent)
            recon_loss = tf.reduce_mean(tf.square(inputs - recon))

        grads = tape.gradient(recon_loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

        # SVDD center update
        batch_center = tf.reduce_mean(latent, axis=0)
        self.center.assign(0.9 * self.center + 0.1 * batch_center)

        return {"loss_ae_total": recon_loss}

    # ==========================================================
    # INFERENCE
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32)
    ])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        latent = self._encode(inputs)
        recon = self._decode(latent)

        recon_error = tf.reduce_mean(tf.square(inputs - recon), axis=[1, 2])
        latent_dist = tf.reduce_sum(tf.square(latent - self.center), axis=1)

        scores = 0.7 * recon_error + 0.3 * latent_dist

        return {
            "anomaly_score": scores,
            "reconstruction": recon
        }

    # ==========================================================
    # BAKE INITIAL WEIGHTS
    # ==========================================================
    def bake_weights(self) -> None:
        self.baked_weights = [
            tf.identity(v) for v in self.persistent_weights
        ]

    # ==========================================================
    # INIT / RESET MODEL
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        for var, init in zip(self.persistent_weights, self.baked_weights):
            var.assign(init)
        
        # SVDD center is already handled by persistent_weights/baked_weights above,
        # but resetting explicitly doesn't hurt.
        self.center.assign(tf.zeros_like(self.center))
        return {"status": tf.constant(1.0)}

    # ==========================================================
    # SAVE WEIGHTS (TFLITE SAFE EXPORT)
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {
            f"val_{i}": v
            for i, v in enumerate(self.persistent_weights)
        }

    # ==========================================================
    # RESTORE WEIGHTS (TFLITE SAFE IMPORT)
    # ==========================================================
    @tf.function
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, var in enumerate(self.persistent_weights):
            var.assign(kwargs[f"val_{i}"])
        return {"status": tf.constant(1.0)}