import tensorflow as tf
from typing import Dict, List
# Assuming BaseAnomalyDetector and config are available in your environment
from .base import BaseAnomalyDetector
from config import SEQUENCE_LENGTH, BATCH_SIZE, SENSOR_INPUT_DIM

class SensorAuthenticator(BaseAnomalyDetector):
    def __init__(
        self,
        input_dim: int,
        seq_len: int,
        batch_size: int = 32,
        learning_rate: float = 1e-3,
        svdd_weight: float = 0.2,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)

        self.seq_len = seq_len
        self.input_dim = input_dim
        self.svdd_weight = svdd_weight
        self.l2_reg = tf.keras.regularizers.l2(1e-4)

        # ----------------------------------------------------------
        # SVDD CENTER (Initialized non-zero to prevent collapse)
        # ----------------------------------------------------------
        self.center = self.add_weight(
            name="svdd_center",
            shape=(32,),
            initializer=tf.keras.initializers.RandomNormal(mean=0.1, stddev=0.05),
            trainable=False
        )

        # ==========================================================
        # 1. DEPTHWISE TEMPORAL CONV BLOCK
        # ==========================================================
        self.dw_conv = tf.keras.layers.SeparableConv1D(
            32, kernel_size=5, padding='same',
            depthwise_regularizer=self.l2_reg,  
            pointwise_regularizer=self.l2_reg   
        )
        # Swapped LayerNorm for BatchNorm for faster execution
        self.norm1 = tf.keras.layers.BatchNormalization()
        self.act1 = tf.keras.layers.ReLU()

        # ==========================================================
        # 2. LIGHTWEIGHT SELF-ATTENTION (LINEAR)
        # ==========================================================
        # 1x1 Conv1D is mathematically identical to Dense for temporal sequences
        # but is often more heavily optimized in TF backends.
        self.q_conv = tf.keras.layers.Conv1D(32, 1, kernel_regularizer=self.l2_reg)
        self.k_conv = tf.keras.layers.Conv1D(32, 1, kernel_regularizer=self.l2_reg)
        self.v_conv = tf.keras.layers.Conv1D(32, 1, kernel_regularizer=self.l2_reg)
        self.attn_out = tf.keras.layers.Conv1D(32, 1, kernel_regularizer=self.l2_reg)
        self.norm2 = tf.keras.layers.BatchNormalization()

        # ==========================================================
        # 3. TEMPORAL POOLING (Replaces the slow GRU)
        # ==========================================================
        # Reduces (Batch, Time, Dim) -> (Batch, Dim) instantly without recurrence
        self.pooling = tf.keras.layers.GlobalAveragePooling1D()

        # ==========================================================
        # 4. LATENT PROJECTION (No Biases)
        # ==========================================================
        self.latent_dense = tf.keras.layers.Dense(
            32, 
            use_bias=False, 
            kernel_regularizer=self.l2_reg
        )

        self.optimizer = tf.keras.optimizers.Adam(learning_rate)

        self._build()
        self.bake_weights()

    def _build(self):
        x = tf.zeros((1, self.seq_len, self.input_dim))
        with tf.GradientTape() as tape:
            z = self._forward(x, training=True)
            loss = tf.reduce_mean(tf.square(z))
        
        grads = tape.gradient(loss, self.trainable_weights)
        grads_and_vars = [(g, w) for g, w in zip(grads, self.trainable_weights) if g is not None]
        if grads_and_vars:
            self.optimizer.apply_gradients(grads_and_vars)

    def _linear_attention(self, x):
        Q = self.q_conv(x)
        K = self.k_conv(x)
        V = self.v_conv(x)

        # Softmax-free linear attention
        K = tf.nn.softmax(K, axis=1)
        KV = tf.einsum("btd,bte->bde", K, V)
        out = tf.einsum("btd,bde->bte", Q, KV)

        return self.attn_out(out)

    def _forward(self, x, training=False):
        # Local temporal patterns
        x = self.dw_conv(x)
        x = self.norm1(x, training=training)
        x = self.act1(x)

        # Global temporal patterns
        attn = self._linear_attention(x)
        x = self.norm2(x + attn, training=training)

        # Sequence dynamics (Now highly parallelized)
        x = self.pooling(x)

        # Latent embedding
        z = self.latent_dense(x)
        return z

    def call(self, inputs, training=False):
        return self._forward(inputs, training=training)

    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    @property
    def persistent_weights(self) -> List[tf.Variable]:
        # BatchNorm adds non-trainable weights (moving mean/variance), 
        # which are automatically handled by this property during save/restore.
        return self.trainable_weights + self.non_trainable_weights + self.optimizer.variables

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[BATCH_SIZE, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32)
    ])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        with tf.GradientTape() as tape:
            latent = self._forward(inputs, training=True)
            
            # SVDD Loss
            distances = tf.reduce_sum(tf.square(latent - tf.stop_gradient(self.center)), axis=1)
            svdd_loss = tf.reduce_mean(distances)
            
            # Add L2 regularization losses
            l2_loss = tf.reduce_sum(self.losses) if self.losses else 0.0
            total_loss = svdd_loss + l2_loss

        grads = tape.gradient(total_loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

        # Update center (EMA) 
        batch_center = tf.reduce_mean(latent, axis=0)
        self.center.assign(0.95 * self.center + 0.05 * batch_center)

        return {
            "loss_ae_total": total_loss,
            "svdd_distance": svdd_loss
        }

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32)
    ])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        latent = self._forward(inputs, training=False)
        scores = tf.reduce_sum(tf.square(latent - self.center), axis=1)
        return {
            "anomaly_score": scores,
            "reconstruction": latent
        }

    def bake_weights(self) -> None:
        self.baked_weights = [tf.identity(v) for v in self.persistent_weights]

    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32)])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        for var, init in zip(self.persistent_weights, self.baked_weights):
            var.assign(init)
        return {"status": tf.constant(1.0)}

    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32)])
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {f"val_{i}": v for i, v in enumerate(self.persistent_weights)}

    @tf.function
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, var in enumerate(self.persistent_weights):
            var.assign(kwargs[f"val_{i}"])
        return {"status": tf.constant(1.0)}