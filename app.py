from flask import Flask, render_template, request
import random

app = Flask(__name__)

# --------- DATA ----------
hooks = [
    "Stop scrolling.",
    "This changes everything.",
    "Nobody tells you this…",
    "Imagine this.",
    "You’re missing out."
]

emotions = [
    "confidence", "power", "freedom", "luxury", "attention"
]

actions = [
    "walking into a room and turning heads",
    "feeling unstoppable during your day",
    "finally solving a frustrating problem",
    "living your dream lifestyle"
]

urgency = [
    "Limited time only.",
    "Act now.",
    "Don’t miss out.",
    "While stocks last."
]

# --------- CLEAN INPUT ----------
def clean_input(text):
    if not text:
        return ""
    text = text.lower().strip()

    corrections = {
        "hedphones": "headphones",
        "earbud": "earbuds",
        "shapwear": "shapewear",
        "gymers": "gym users"
    }

    for wrong, correct in corrections.items():
        text = text.replace(wrong, correct)

    return text


# --------- MAIN ----------
@app.route("/", methods=["GET", "POST"])
def index():
    result = None
    score = None
    tips = []
    improved = None

    if request.method == "POST":
        product = clean_input(request.form.get("product"))
        audience = clean_input(request.form.get("audience"))
        goal = clean_input(request.form.get("goal"))
        style = clean_input(request.form.get("style"))

        if not goal:
            goal = "increase sales"
        if not style:
            style = "emotional"

        hook = random.choice(hooks)
        emotion = random.choice(emotions)
        action = random.choice(actions)
        urgent = random.choice(urgency)

        result = f"""
Hook: {hook}

Scene:
A {audience} is {action}, feeling {emotion}.

Problem:
They struggle with something frustrating in daily life.

Solution:
Then they discover {product}, which changes everything.

Style: {style}
Goal: {goal}

CTA: {urgent}
"""

        score = 5
        if len(product) > 6: score += 1
        if len(audience) > 6: score += 1
        if goal: score += 1
        if style: score += 1
        if "luxury" in style or "emotional" in style: score += 1
        if score > 10: score = 10

        if len(product) < 6:
            tips.append("Describe your product more clearly.")
        if len(audience) < 6:
            tips.append("Make your audience more specific.")
        if "luxury" not in style:
            tips.append("Try stronger styles like 'luxury' or 'emotional'.")
        if "sales" not in goal:
            tips.append("Focus your goal on results like sales or conversions.")

        improved = f"""
{hook}

Imagine {action}.

With {product}, you unlock {emotion} and transform your experience.

Built for {audience}, this is not just a product — it’s a lifestyle upgrade.

{urgent}
"""

    return render_template(
        "index.html",
        result=result,
        score=score,
        tips=tips,
        improved=improved
    )


@app.route("/login")
def login():
    return render_template("login.html")


@app.route("/signup")
def signup():
    return render_template("signup.html")


if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=8000)
