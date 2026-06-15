import os

def search_cookies_in_code():
    mine_dir = r"C:\Users\kshitij\Desktop\mine"
    print("Searching for cookies usage in Kotlin code...")
    for root, dirs, files in os.walk(mine_dir):
        for f in files:
            if f.endswith(".kt"):
                path = os.path.join(root, f)
                try:
                    with open(path, "r", encoding="utf-8") as file:
                        content = file.read()
                        if "cookies" in content.lower():
                            print(f"Found cookies in: {path}")
                            # Print lines containing cookies
                            lines = content.splitlines()
                            for idx, line in enumerate(lines):
                                if "cookies" in line.lower():
                                    print(f"  Line {idx+1}: {line.strip()}")
                except Exception:
                    pass

if __name__ == '__main__':
    search_cookies_in_code()
