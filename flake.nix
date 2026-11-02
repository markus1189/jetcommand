{
  description = "JetCommand: native Android BedJet controller";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
      buildToolsVersion = "37.0.0";
      android = pkgs.androidenv.composeAndroidPackages {
        # 36 only for the emulator image, which matches targetSdk.
        platformVersions = [ "36" "37" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
        includeNDK = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      emu = pkgs.writeShellApplication {
        name = "emu";
        runtimeInputs = [ android.androidsdk ];
        text = ''
          avd=jetcommand
          if ! avdmanager list avd -c | grep -qx "$avd"; then
            echo no | avdmanager create avd -n "$avd" -d pixel_7 \
              -k "system-images;android-36;google_apis;x86_64"
          fi
          exec emulator -avd "$avd" -no-window -no-audio -no-boot-anim \
            -gpu swiftshader_indirect -no-snapshot-save "$@"
        '';
      };
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ android.androidsdk emu pkgs.jdk21 pkgs.gradle_9 pkgs.sqlite ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        JAVA_HOME = pkgs.jdk21.home;
        # AGP fetches aapt2 from Maven as a dynamically linked binary, which NixOS can't run.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
      };
    };
}
