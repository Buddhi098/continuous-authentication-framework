# ContinuousAuth – Android Continuous Authentication Framework

🚀 **ContinuousAuth** is a full-featured **continuous authentication framework and Android application** designed to continuously verify a user’s identity during device usage using behavioral and sensor-based signals.

The framework is built with a **modular architecture**, supports **on-device deep learning using TensorFlow Lite (TFLite)**, and is intended for **research, experimentation, and real-world deployment** on Android devices.

⚠️ This is an **ongoing project**  
✅ **Current stable branch:** `buddhi-v11`

---

## Key Features

- Android-native continuous authentication framework  
- On-device deep learning using TensorFlow Lite (TFLite)  
- Continuous enrollment and adaptive authentication  
- Privacy-preserving (no cloud dependency)  
- Modular and extensible architecture  
- Designed for research and real-world deployment  
- Fully integrated Android application  

---

## Architecture Overview

The framework follows a **layered and modular architecture** separating concerns such as data collection, feature processing, modeling, and authentication logic.

### Main Layers

- Raw data collection from sensors and behavioral sources  
- Feature extraction, processing, and fusion  
- Authentication engine for scoring and decision making  
- On-device ML models for training and inference  
- Configuration, state, and utility management  

---

## Project Structure

continuousauth/  
├── manifests/  
├── kotlin+java/  
│   └── com.ca.continuousauth  
│       ├── authengine  
│       │   ├── AdaptiveScoreDenoiser  
│       │   ├── AuthenticationManager  
│       │   └── EnrollmentManager  
│       │  
│       ├── authmodel  
│       │   └── AuthModel  
│       │  
│       ├── config  
│       │   ├── AuthConfig  
│       │   └── AuthConfigManager  
│       │  
│       ├── data  
│       │  
│       ├── featuremodalities  
│       │   ├── dataprocessing  
│       │   ├── featurefusion  
│       │   ├── featurepipeline  
│       │   ├── rawdatacollectors  
│       │   └── FeatureModel  
│       │  
│       ├── states  
│       ├── utils  
│       └── ContinuousAuth  
│  
├── com.ca.continuousauth (androidTest)  
└── com.ca.continuousauth (test)  

---

## Technology Stack

- Platform: Android  
- Languages: Kotlin, Python  
- Deep Learning: TensorFlow Lite (TFLite)  
- IDE: Android Studio  

### DL Strategy

- Fully on-device model training  
- Fully on-device inference  
- No server or cloud dependency  

---

## Android Application

This repository includes a **fully functional Android application** that integrates the ContinuousAuth framework.

You can:
- Run the application directly from Android Studio  
- Test continuous authentication behavior in real time  
- Extend the framework with new modalities or models  

---

## Getting Started

### Prerequisites

- Android Studio (latest recommended)  
- Android SDK  
- Physical Android device (recommended for sensor accuracy)  

### Running the Project

1. Clone the repository  
   git clone <repository-url>

2. Open the project in Android Studio  

3. Checkout the stable branch  
   git checkout buddhi-v11  

4. Sync Gradle  

5. Run the application on an emulator or physical device  

---

## Deep Learning Details

- Uses TensorFlow Lite for efficient on-device ML  
- Supports continuous learning and adaptive scoring  
- Optimized for mobile performance and low latency  
- Designed with a privacy-first approach  

---

## Project Status

- Core framework implemented  
- Android application integrated  
- On-device ML training and inference working  
- Active development and ongoing improvements  

---

## Roadmap (Planned)

- Additional behavioral and sensor modalities  
- Improved feature fusion techniques  
- Model optimization and quantization  
- Performance benchmarking  
- Extended documentation and examples  
- Research publication support  

---

## License

License information will be added.

---

## Author

**Buddhi Nadeeshan**  
Continuous Authentication • Android • On-device AI • Security Research
