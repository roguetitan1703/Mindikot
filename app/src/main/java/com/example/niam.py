import os
import json

def reverse_project_generator(root_dir):
    """
    Generates a project_config.json structure from an existing directory.

    Args:
        root_dir (str): Path to the root directory of the project.

    Returns:
        dict: A dictionary representing the project structure in JSON format,
              or None if an error occurs.
    """

    if not os.path.isdir(root_dir):
        print(f"Error: '{root_dir}' is not a valid directory.")
        return None

    project_structure = _generate_structure_recursive(root_dir)

    if project_structure:
        config_json = {
            "project_name": os.path.basename(root_dir), # Optional project name - based on root dir name
            "structure": project_structure
        }
        return config_json
    else:
        return None


def _generate_structure_recursive(current_dir):
    """
    Recursively generates the structure data for a directory.

    Args:
        current_dir (str): The current directory being processed.

    Returns:
        dict: A dictionary representing the structure of the current directory and its contents.
              Returns None if an error occurs during file reading.
    """
    structure_data = {}
    try:
        for item_name in os.listdir(current_dir):
            item_path = os.path.join(current_dir, item_name)

            if os.path.isdir(item_path):
                # Recursive call for subdirectory
                subdirectory_structure = _generate_structure_recursive(item_path)
                if subdirectory_structure is not None: # Handle potential errors in subdirectory recursion
                    structure_data[item_name] = subdirectory_structure
                else:
                    return None # Propagate error upwards

            elif os.path.isfile(item_path):
                # Read file content
                try:
                    with open(item_path, 'r') as f:
                        file_content = f.read() # Read entire file content as string
                except Exception as e:
                    print(f"Error reading file '{item_path}': {e}")
                    return None # Indicate error reading file
                structure_data[item_name] = file_content

    except OSError as e: # Catch directory listing errors
        print(f"Error listing directory '{current_dir}': {e}")
        return None # Indicate error listing directory

    return structure_data


if __name__ == "__main__":
    dirr = os.getcwd()
    target_directory = dirr+"\mindikot" # Example: Reverse generate from the module we created

    print(target_directory)
    if not os.path.exists(target_directory) or not os.path.isdir(target_directory):
        print(f"Error: Directory '{target_directory}' not found. Please create it first or specify a valid directory.")
    else:
        config_data = reverse_project_generator(target_directory)

        if config_data:
            output_json_file = "reversed_project_config.json"
            with open(output_json_file, 'w') as f:
                json.dump(config_data, f, indent=4)
            print(f"Project configuration JSON written to '{output_json_file}'.")
        else:
            print("Project configuration generation failed.")