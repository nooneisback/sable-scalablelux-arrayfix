# AI disclosure
This project is almost entirely AI generated. I'm not going to hide this. I just want to play the game with all the mods I want, not spend 2 weeks reading someone else's code. If you're wondering what I used, it's GLM-5.3 through 9router and headroom.

# What this does?
Sable and ScalableLux are incompatible due to the way Sable reports block positions. ScalableLux thinks it has to update lighting at a location that isn't loaded, which causes a crash with ArrayIndexOutOfBoundsException. This mod solves this by simply ignoring these wrong updates.

# How to install
Tested with Sable 2.0.6 and 0.3.0.
Build the mod with gradlew build. Make sure you gradlew --version returns Java 21.
The built mod will be in builds/libs/*.jar
This mod does not modify Sable's explicit incompatibility with ScalableLux. You'll still have to either edit Sable's jar file directly, mess around with fml.toml, or install a mod like Scalable Lux x Sable Compat.
