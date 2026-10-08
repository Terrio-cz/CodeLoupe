package codeloupe.docker

/** The Docker Engine cannot be reached, or answered something unusable. */
class DockerUnavailable(message: String) : RuntimeException(message)
