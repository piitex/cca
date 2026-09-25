# CCA (RenGL)
This is a RenGL port of character-chat-app. This is a prototype meant to demonstrate the engines capabilities.
The styling and design is a significant improvement compared to JavaFX version.

## Prototype
Highly untested. Make an issue report if you come across bugs. My Focus is on RenJava but I will try to updating this project periodically.

## RenGL
RenGL is a new engine I made to replace JavaFX. It uses pure Java to style components rather than a half-baked css implementation.

**IMPORTANT** RenGL isn't published on GitHub yet. I provided the compiled RenGL jar files that you can manually add to classpath or m2 folder.
Instructions Below:

```commandline
cd libs/
./add_to_maven.sh
```

**Issues**: Please report any app or engine issues. I'm stuck on MacOS for the next month and I'm unable to test and verify and Windows and Linux.
All I can do is verify that it works on my computer and that's it.

## AI Transparency
AI was used in developing this. I originally made character-chat-app and I didn't want to spend a year on this port.
I used AI to convert the project and to structure the UI.

## Notable Features
Some of the features are only for demonstration purposes.

* Custom theming (in app)
* Modern and slick UI
* Interactable/animated/physics background
* Custom downloading handler/container
* Spell Checker (Does not check grammar)
* Hugging-face API
* Cloud computing support
* App protected password

Don't want to download and run the app to see the engine?
(Take a look at [screenshots](images/))!!!