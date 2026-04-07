# VideoPlayerPlugin (Paper 1.21.6)

Plugin Paper que adapta la parte servidor de `VideoPlayerMod`.

## Qué replica del mod

- Comando `/cinematic` con subcomandos:
  - `/cinematic play <id> [targets]`
  - `/cinematic stop [targets]`
  - `/cinematic list`
  - `/cinematic volume <0-100>`
- Misma lógica de carga de videos:
  - `config/videoplayermod/videos.properties`
  - `config/videoplayermod/urls.properties`
  - `config/videoplayermod/videos/*.mp4`
- Misma idea de permisos tipo OP (default `op` en `plugin.yml`)
- Targets con `@a`, `@p`, `@s` y nombre de jugador
- Envío de payloads en canales:
  - `videoplayermod:play_video`
  - `videoplayermod:stop_video`
  - `videoplayermod:set_volume`

## Requisito importante

Para tener **render y audio exactamente como tu mod**, el cliente del jugador debe seguir usando el mod `VideoPlayerMod`, porque el render HUD/decodificación está del lado cliente.

Este plugin reemplaza la parte servidor (comandos + broadcast de payloads) para Paper.

## Build

```powershell
./gradlew build
```

El JAR se genera en `build/libs/`.

## Instalación

1. Copia el JAR del plugin en la carpeta `plugins/` del servidor Paper 1.21.6.
2. Inicia el servidor una vez para crear `config/videoplayermod/`.
3. Coloca videos `.mp4` en `config/videoplayermod/videos/` o usa `urls.properties`.
4. Asegúrate de que los jugadores tengan instalado el cliente `VideoPlayerMod`.
