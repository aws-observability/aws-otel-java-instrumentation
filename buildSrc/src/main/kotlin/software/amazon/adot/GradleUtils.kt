package software.amazon.adot

import com.google.cloud.tools.jib.gradle.JibExtension

/**
 * Utilitary extension function used to configure jib according to flags. Used to avoid having branching in each build
 * script.
 */
fun JibExtension.configureImages(sourceImage:String, destinationImage: String, localDocker: Boolean, multiPlatform: Boolean, tags: Set<String> = setOf<String>()) {
  to {
    image = destinationImage
    if (!tags.isEmpty()) {
      this.tags = tags;
    }
  }

  from {
    if (localDocker) {
      image = "docker://$sourceImage"
    } else {
      image = sourceImage
    }

    if (multiPlatform) {
      platforms {
        platform {
          architecture = "amd64"
          os = "linux"
        }
        platform {
          architecture = "arm64"
          os = "linux"
        }
      }
    }
  }
}
