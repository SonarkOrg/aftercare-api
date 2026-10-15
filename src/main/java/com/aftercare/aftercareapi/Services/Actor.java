package com.aftercare.aftercareapi.Services;

/** Who performed an action: the Auth0 subject plus a display-name snapshot (null when unknown). */
public record Actor(String sub, String name) {
}
