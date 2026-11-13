#pragma once
#include <stdbool.h>
#include <stddef.h>

// Validate and install a staged DLC image, keeping the prior image and loose files if a step fails.
bool dlc_install_image(const char *source, const char *dir, bool (*keep)(const char *), char *why, size_t why_size);
