/**
 * Small, dependency-free REST, static-file and Server-Sent Events server on the JDK's
 * {@code com.sun.net.httpserver}, with its own JSON writer ({@link com.simweaver.common.rest.Jsons})
 * and parser ({@link com.simweaver.common.rest.JsonParser}).
 *
 * <p>Continuation of xrest 0.0.6 ({@code cloud.pactl.xrest}) with the same class names; see
 * docs/FIXES.md for what changed. Entry points: {@link com.simweaver.common.rest.XrServer},
 * {@link com.simweaver.common.rest.ApiCallHandler} / {@link com.simweaver.common.rest.ApiCallHandlerWithBody},
 * {@link com.simweaver.common.rest.XrSseHub}.
 */
package com.simweaver.common.rest;
