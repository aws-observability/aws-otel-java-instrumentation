/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.sampleapp;

import static spark.Spark.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class App {

  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  public static void main(String[] args) throws IOException, InterruptedException {
    port(Integer.parseInt("8080"));
    ipAddress("0.0.0.0");

    get(
        "/success",
        (req, res) -> {
          HttpRequest request =
              HttpRequest.newBuilder()
                  .GET()
                  .uri(URI.create("http://backend:8080/backend/success"))
                  .build();

          HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          res.status(response.statusCode());
          res.body(response.body());
          return "";
        });

    get(
        "/error",
        (req, res) -> {
          HttpRequest request =
              HttpRequest.newBuilder()
                  .GET()
                  .uri(URI.create("http://backend:8080/backend/error"))
                  .build();

          HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          res.status(response.statusCode());
          res.body(response.body());
          return "";
        });

    get(
        "/fault",
        (req, res) -> {
          HttpRequest request =
              HttpRequest.newBuilder()
                  .GET()
                  .uri(URI.create("http://backend:8080/backend/fault"))
                  .build();

          HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          res.status(response.statusCode());
          res.body(response.body());
          return "";
        });

    get(
        "/backend/success",
        (req, res) -> {
          res.status(200);
          return res;
        });

    get(
        "/backend/error",
        (req, res) -> {
          res.status(400);
          return res;
        });
    get(
        "/backend/fault",
        (req, res) -> {
          res.status(500);
          return res;
        });

    post(
        "/success/postmethod",
        (req, res) -> {
          HttpRequest request =
              HttpRequest.newBuilder()
                  .POST(HttpRequest.BodyPublishers.ofString(req.body()))
                  .uri(URI.create("http://backend:8080/backend/success/postmethod"))
                  .build();

          HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          res.status(response.statusCode());
          res.body(response.body());
          return res.body();
        });

    post(
        "/error/postmethod",
        (req, res) -> {
          HttpRequest request =
              HttpRequest.newBuilder()
                  .POST(HttpRequest.BodyPublishers.ofString(req.body()))
                  .uri(URI.create("http://backend:8080/backend/error/postmethod"))
                  .build();

          HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          res.status(response.statusCode());
          res.body(response.body());
          return res.body();
        });

    post(
        "/fault/postmethod",
        (req, res) -> {
          HttpRequest request =
              HttpRequest.newBuilder()
                  .POST(HttpRequest.BodyPublishers.ofString(req.body()))
                  .uri(URI.create("http://backend:8080/backend/fault/postmethod"))
                  .build();

          HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          res.status(response.statusCode());
          res.body(response.body());
          return res.body();
        });

    post(
        "/backend/success/postmethod",
        (req, res) -> {
          res.status(200);
          res.body(req.body());
          return res.body();
        });

    post(
        "/backend/error/postmethod",
        (req, res) -> {
          res.status(400);
          res.body(req.body());
          return res.body();
        });

    post(
        "/backend/fault/postmethod",
        (req, res) -> {
          res.status(500);
          res.body(req.body());
          return res.body();
        });
  }
}
