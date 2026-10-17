# Short description
This is a neoforge 1.21.1 mod project that provides compatibility between Sable and ScalableLux mods.

# The problem
When creating new Sable objects, ArrayIndexOutOfBoundsException is sometimes thrown. 

# Crash log

```
java.lang.ArrayIndexOutOfBoundsException: Index -7682932 out of bounds for length 25
	at TRANSFORMER/scalablelux@0.3.0-alpha.0.8+1.21.1/ca.spottedleaf.starlight.common.light.StarLightEngine.getEmptinessMap(StarLightEngine.java:384) ~[ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar%23413!/:?] {re:mixin,re:computing_frames,re:classloading}
	at TRANSFORMER/scalablelux@0.3.0-alpha.0.8+1.21.1/ca.spottedleaf.starlight.common.light.StarLightEngine.handleEmptySectionChanges(StarLightEngine.java:747) ~[ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar%23413!/:?] {re:mixin,re:computing_frames,re:classloading}
	at TRANSFORMER/scalablelux@0.3.0-alpha.0.8+1.21.1/ca.spottedleaf.starlight.common.light.StarLightEngine.blocksChangedInChunk(StarLightEngine.java:424) ~[ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar%23413!/:?] {re:mixin,re:computing_frames,re:classloading}
	at TRANSFORMER/scalablelux@0.3.0-alpha.0.8+1.21.1/ca.spottedleaf.starlight.common.light.StarLightInterface.handleUpdateInternal(StarLightInterface.java:659) ~[ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar%23413!/:?] {re:mixin,re:classloading,pl:mixin:APP:sablescalablelux.mixins.json:StarLightInterfaceMixin from mod sablescalablelux,pl:mixin:A}
	at TRANSFORMER/scalablelux@0.3.0-alpha.0.8+1.21.1/ca.spottedleaf.starlight.common.light.StarLightInterface.propagateChanges(StarLightInterface.java:579) ~[ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar%23413!/:?] {re:mixin,re:classloading,pl:mixin:APP:sablescalablelux.mixins.json:StarLightInterfaceMixin from mod sablescalablelux,pl:mixin:A}
	at TRANSFORMER/scalablelux@0.3.0-alpha.0.8+1.21.1/ca.spottedleaf.starlight.common.light.vanillainterface.CommonLightEngineUtils.runLightUpdates(CommonLightEngineUtils.java:28) ~[ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar%23413!/:?] {re:mixin,re:classloading}
	at TRANSFORMER/scalablelux@0.3.0-alpha.0.8+1.21.1/ca.spottedleaf.starlight.common.light.vanillainterface.BaseLevelLightEngineVanillaInterface.runLightUpdates(BaseLevelLightEngineVanillaInterface.java:57) ~[ScalableLux-neoforge-0.3.0-alpha.0.8-all.jar%23413!/:?] {re:mixin,re:classloading}
```