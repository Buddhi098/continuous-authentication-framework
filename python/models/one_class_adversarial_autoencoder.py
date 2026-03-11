import tensorflow as tf
from typing import Dict, List
from .base import BaseAnomalyDetector
import math

# =========================================================
# Global Hyperparameters for Fine-Tuning
# =========================================================
# Data Dimensions
INPUT_DIM = 12
SEQUENCE_LENGTH = 200
BATCH_SIZE = 16

# Latent Space
LATENT_DIM = 128
NOISE_STDDEV = 0.1           # Noise added to inputs during training

# Loss Weights
LAMBDA_REC = 50.0           # Weight of MSE reconstruction loss vs GAN loss

# Learning Rates
LR_ENC_DEC = 1e-4            # Autoencoder learning rate
LR_S_DISC = 1e-4             # Sample discriminator learning rate
LR_L_DISC = 1e-4             # Latent discriminator learning rate

# Attention Module Settings
SE_REDUCTION = 8             # Squeeze-and-Excitation reduction ratio
CBAM_REDUCTION = 8           # CBAM channel reduction ratio
CBAM_SPATIAL_KERNEL = 5  #3      # CBAM spatial convolution kernel size

# Architecture Tweaks
LEAKY_RELU_ALPHA = 0.2       # Slope for LeakyReLU layers
# =========================================================


# ---------------------------------------------------------
# CBAM: Channel + Spatial Attention Module
# ---------------------------------------------------------
class CBAM(tf.keras.layers.Layer):
    def __init__(self, channels, reduction=CBAM_REDUCTION, spatial_kernel=CBAM_SPATIAL_KERNEL):
        super().__init__()
        # Channel attention
        self.gap = tf.keras.layers.GlobalAveragePooling2D()
        self.gmp = tf.keras.layers.GlobalMaxPooling2D()
        self.fc1 = tf.keras.layers.Dense(channels // reduction, activation='relu')
        self.fc2 = tf.keras.layers.Dense(channels, activation='sigmoid')
        
        # Spatial attention
        self.conv_spatial = tf.keras.layers.Conv2D(1, kernel_size=spatial_kernel, padding='same', activation='sigmoid')

    def call(self, x):
        # Channel Attention
        avg = self.fc2(self.fc1(self.gap(x)))
        max_ = self.fc2(self.fc1(self.gmp(x)))
        ca = tf.expand_dims(tf.expand_dims(avg + max_, 1), 1)
        x = x * (1 + ca)

        # Spatial Attention
        max_c = tf.reduce_max(x, axis=-1, keepdims=True)
        avg_c = tf.reduce_mean(x, axis=-1, keepdims=True)
        sa = self.conv_spatial(tf.concat([max_c, avg_c], axis=-1))
        return x * sa


# ---------------------------------------------------------
# One-Class Adversarial Autoencoder
# ---------------------------------------------------------
class OneClassAdversarialAutoencoder(BaseAnomalyDetector):

    def __init__(self, input_dim=INPUT_DIM, sequence_length=SEQUENCE_LENGTH, batch_size=BATCH_SIZE, latent_dim=LATENT_DIM, **kwargs):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)
        self.sequence_length = sequence_length
        self.latent_dim = latent_dim
        self.lambda_rec = LAMBDA_REC  

        # =========================================================
        # DYNAMIC SHAPE CALCULATION
        # Convolutions with stride=2 and padding='same' halve the size (ceil).
        # We compute exactly what the dimensions will be at the bottleneck.
        # =========================================================
        h1 = math.ceil(input_dim / 2)
        w1 = math.ceil(sequence_length / 2)
        h2 = math.ceil(h1 / 2)
        w2 = math.ceil(w1 / 2)
        h3 = math.ceil(h2 / 2)
        w3 = math.ceil(w2 / 2)

        # Calculate exact crops needed to reverse the downsampling
        crop_h1, crop_w1 = (h3 * 2) - h2, (w3 * 2) - w2
        crop_h2, crop_w2 = (h2 * 2) - h1, (w2 * 2) - w1
        crop_h3, crop_w3 = (h1 * 2) - input_dim, (w1 * 2) - sequence_length

        # ---------------- Encoder ----------------
        self.encoder = tf.keras.Sequential([
            tf.keras.layers.InputLayer(input_shape=(input_dim, sequence_length, 1)),

            tf.keras.layers.Conv2D(32, 3, strides=2, padding='same'),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.ReLU(),
            CBAM(32),

            tf.keras.layers.Conv2D(64, 3, strides=2, padding='same'),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.ReLU(),
            CBAM(64),  

            tf.keras.layers.Conv2D(128, 3, strides=2, padding='same'),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.ReLU(),

            tf.keras.layers.Flatten(),
            tf.keras.layers.Dense(latent_dim)
        ], name='encoder')

        # ---------------- Decoder ----------------
        self.decoder = tf.keras.Sequential([
            tf.keras.layers.InputLayer(shape=(latent_dim,)),

            # Use dynamically calculated bottleneck dimensions
            tf.keras.layers.Dense(h3 * w3 * 128),
            tf.keras.layers.Reshape((h3, w3, 128)),

            # Residual ConvTranspose Block 1
            tf.keras.layers.Conv2DTranspose(64, 3, strides=2, padding='same'),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.ReLU(),
            CBAM(64),
            tf.keras.layers.Cropping2D(((0, crop_h1), (0, crop_w1))),

            # Residual ConvTranspose Block 2
            tf.keras.layers.Conv2DTranspose(32, 3, strides=2, padding='same'),
            tf.keras.layers.LayerNormalization(),
            tf.keras.layers.ReLU(),
            CBAM(32),
            tf.keras.layers.Cropping2D(((0, crop_h2), (0, crop_w2))),

            # Final Block
            tf.keras.layers.Conv2DTranspose(1, 3, strides=2, padding='same', activation='sigmoid'),
            tf.keras.layers.Cropping2D(((0, crop_h3), (0, crop_w3))) # Ensures perfect final dimension match
        ], name='decoder')

        # ---------------- Latent Discriminator ----------------
        self.latent_discriminator = tf.keras.Sequential([
            tf.keras.layers.InputLayer(input_shape=(latent_dim,)),
            tf.keras.layers.Dense(128), tf.keras.layers.LeakyReLU(LEAKY_RELU_ALPHA),
            tf.keras.layers.Dense(64), tf.keras.layers.LeakyReLU(LEAKY_RELU_ALPHA),
            tf.keras.layers.Dense(1, activation='sigmoid')
        ], name='latent_discriminator')

        # ---------------- Sample Discriminator (Patch-style) ----------------
        self.sample_discriminator = tf.keras.Sequential([
            tf.keras.layers.InputLayer(input_shape=(input_dim, sequence_length, 1)),
            tf.keras.layers.Conv2D(32, 3, strides=2, padding='same'), tf.keras.layers.LeakyReLU(LEAKY_RELU_ALPHA),
            tf.keras.layers.Conv2D(64, 3, strides=2, padding='same'), tf.keras.layers.LeakyReLU(LEAKY_RELU_ALPHA),
            tf.keras.layers.Conv2D(128, 3, strides=2, padding='same'), tf.keras.layers.LeakyReLU(LEAKY_RELU_ALPHA),
            tf.keras.layers.Conv2D(1, 3, strides=1, padding='same', activation='sigmoid') 
        ], name='sample_discriminator')

        # ---------------- Optimizers ----------------
        self.enc_dec_opt = tf.keras.optimizers.Adam(LR_ENC_DEC)
        self.l_disc_opt = tf.keras.optimizers.Adam(LR_L_DISC)
        self.s_disc_opt = tf.keras.optimizers.Adam(LR_S_DISC)
        self.bce = tf.keras.losses.BinaryCrossentropy()

        # Bake weights placeholder
        self.baked_weights = []

    # ---------------- Persistent Variables ----------------
    @property
    def persistent_weights(self) -> List[tf.Variable]:
        base_vars = [v for v in self.variables if 'seed_generator' not in v.name]
        opt_vars = []
        for opt in [self.enc_dec_opt, self.l_disc_opt, self.s_disc_opt]:
            if hasattr(opt, 'variables'):
                opt_vars.extend(opt.variables() if callable(opt.variables) else opt.variables)
        all_vars = base_vars + opt_vars
        unique_vars = []
        seen = set()
        for v in all_vars:
            if id(v) not in seen:
                seen.add(id(v))
                unique_vars.append(v)
        return unique_vars

    @property
    def signature_keys(self) -> List[str]:
        return ['train', 'infer', 'init_model', 'save', 'restore']

    # ---------------- Forward Pass ----------------
    def call(self, inputs: tf.Tensor, training=False):
        z = self.encoder(inputs, training=training)
        recon = self.decoder(z, training=training)
        return recon

    # ---------------- Training Function ----------------
    @tf.function(input_signature=[tf.TensorSpec(shape=[None, INPUT_DIM, SEQUENCE_LENGTH, 1], dtype=tf.float32)])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        batch = tf.shape(inputs)[0]
        noise = tf.random.normal(tf.shape(inputs), stddev=NOISE_STDDEV)
        noisy_inputs = inputs + noise
        prior_z = tf.random.normal([batch, self.latent_dim])

        with tf.GradientTape(persistent=True) as tape:
            z = self.encoder(noisy_inputs, training=True)
            recon = self.decoder(z, training=True)
            
            # Discriminator critiques the autoencoder's actual outputs against real data
            d_real = self.sample_discriminator(inputs, training=True)
            d_fake = self.sample_discriminator(recon, training=True) 
            
            l_real = self.latent_discriminator(prior_z, training=True)
            l_fake = self.latent_discriminator(z, training=True)

            # Weighted reconstruction loss
            weights = tf.ones_like(inputs)
            rec_loss = tf.reduce_mean(weights * tf.square(inputs - recon))

            # Discriminator losses
            sample_d_loss = self.bce(tf.ones_like(d_real), d_real) + self.bce(tf.zeros_like(d_fake), d_fake)
            latent_d_loss = self.bce(tf.ones_like(l_real), l_real) + self.bce(tf.zeros_like(l_fake), l_fake)

            # Generator losses
            sample_g_loss = self.bce(tf.ones_like(d_fake), d_fake)
            latent_g_loss = self.bce(tf.ones_like(l_fake), l_fake)

            # Total Autoencoder Loss
            ae_loss = self.lambda_rec * rec_loss + sample_g_loss + latent_g_loss

        # Apply gradients with clipping
        ae_grads = tape.gradient(ae_loss, self.encoder.trainable_variables + self.decoder.trainable_variables)
        ae_grads, _ = tf.clip_by_global_norm(ae_grads, 1.0)
        self.enc_dec_opt.apply_gradients(zip(ae_grads, self.encoder.trainable_variables + self.decoder.trainable_variables))

        s_grads = tape.gradient(sample_d_loss, self.sample_discriminator.trainable_variables)
        s_grads, _ = tf.clip_by_global_norm(s_grads, 1.0)
        self.s_disc_opt.apply_gradients(zip(s_grads, self.sample_discriminator.trainable_variables))

        l_grads = tape.gradient(latent_d_loss, self.latent_discriminator.trainable_variables)
        l_grads, _ = tf.clip_by_global_norm(l_grads, 1.0)
        self.l_disc_opt.apply_gradients(zip(l_grads, self.latent_discriminator.trainable_variables))

        del tape
        return {
            'loss_rec': rec_loss,
            'loss_sample_disc': sample_d_loss,
            'loss_latent_disc': latent_d_loss,
            'loss_sample_gen': sample_g_loss,
            'loss_latent_gen': latent_g_loss,
            'loss_ae_total': ae_loss
        }

    # ---------------- Inference ----------------
    @tf.function(input_signature=[tf.TensorSpec(shape=[None, INPUT_DIM, SEQUENCE_LENGTH, 1], dtype=tf.float32)])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        recon = self.call(inputs, training=False)
        mse = tf.reduce_mean(tf.square(inputs - recon), axis=[1,2,3])
        return {'anomaly_score': mse}

    # ---------------- Init Model ----------------
    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32)])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        if len(self.baked_weights) > 0:
            for v, baked_v in zip(self.persistent_weights, self.baked_weights):
                v.assign(baked_v)
        return {'status': tf.constant(1.0)}

    # ---------------- Bake Weights ----------------
    def bake_weights(self):
        self.baked_weights = [tf.identity(v) for v in self.persistent_weights]

    # ---------------- Save ----------------
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {f'val_{i}': v for i, v in enumerate(self.persistent_weights)}

    # ---------------- Restore ----------------
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, v in enumerate(self.persistent_weights):
            v.assign(kwargs[f'val_{i}'])
        return {'status': tf.constant(1.0)}