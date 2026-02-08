"""
Verification script for LiteRT model compatibility.
Loads the generated .tflite model using the ai_edge_litert runtime
and performs a dummy inference to ensure it works as expected.
"""
import numpy as np
import ai_edge_litert.interpreter as litert
from config import TFLITE_FILE_PATH, DEFAULT_INPUT_DIM

def verify_litert_model():
    print("=" * 60)
    print("Verifying LiteRT Model Compatibility...")
    print(f"Model Path: {TFLITE_FILE_PATH}")
    
    try:
        # Load the LiteRT model
        interpreter = litert.Interpreter(model_path=str(TFLITE_FILE_PATH))
        interpreter.allocate_tensors()

        # Print available signatures
        signatures = interpreter.get_signature_list()
        print("\nAvailable Signatures:")
        print(signatures)

        # 1. Initialize the model (required for resource variables)
        if "init_model" in signatures:
            print("\nInitializing model variables...")
            init_runner = interpreter.get_signature_runner("init_model")
            # init_model takes a dummy input 'x'
            init_runner(x=np.array([1.0], dtype=np.float32))
            print("Model initialized.")

        # 2. Run Inference
        if "infer" in signatures:
            print("\nRunning Inference...")
            infer_runner = interpreter.get_signature_runner("infer")
            
            # Get input details from signature runner to know input name/shape
            # infer_runner.get_input_details() might not be directly available on the runner object in all versions,
            # but we can try to assume the input name or inspect. 
            # However, for a signature runner, we pass arguments by name.
            # The signature input name for 'infer' in VAE is likely 'inputs' based on the tf.function signature?
            # BaseAnomalyDetector: infer_func(self, inputs) -> inputs is the argument name.
            
            # Let's inspect the input details from the main interpreter to see shapes if needed,
            # or just assume DEFAULT_INPUT_DIM from config.
            input_data = np.random.random_sample((1, DEFAULT_INPUT_DIM)).astype(np.float32)
            
            # 'inputs' is the argument name in the python function definition
            output = infer_runner(inputs=input_data)
            
            print("\nInference Result Keys:")
            print(output.keys())
            
            if "reconstruction" in output:
                print("\nReconstruction (First 5 values):")
                print(output["reconstruction"].flatten()[:5])

            if "anomaly_score" in output:
                 print("\nAnomaly Score:")
                 print(output["anomaly_score"])

            print("\n" + "=" * 60)
            print("SUCCESS: LiteRT Inference Successful (with signatures)")
            print("=" * 60)
            return True
        else:
             print("FAILURE: 'infer' signature not found.")
             return False

    except Exception as e:
        print("\n" + "!" * 60)
        print(f"FAILURE: LiteRT Verification Failed: {e}")
        print("!" * 60)
        return False

if __name__ == "__main__":
    verify_litert_model()
