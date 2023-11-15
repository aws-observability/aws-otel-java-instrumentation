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
import java.util.ArrayList;
import java.util.List;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import spark.Response;

public class App {
  public static void main(String[] args) {
    port(Integer.parseInt("8080"));
    ipAddress("0.0.0.0");
    get(
        "/success",
        (req, res) -> {
          httpGet(res, "http://backend:8080/backend/success");
          return "";
        });

    get(
        "/error",
        (req, res) -> {
          httpGet(res, "http://backend:8080/backend/error");
          return "";
        });

    get(
        "/fault",
        (req, res) -> {
          httpGet(res, "http://backend:8080/backend/fault");
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
          sendPost(res, "http://backend:8080/backend/success/postmethod");
          return res.body();
        });

    post(
        "/error/postmethod",
        (req, res) -> {
          sendPost(res, "http://backend:8080/backend/error/postmethod");
          return res.body();
        });

    post(
        "/fault/postmethod",
        (req, res) -> {
          sendPost(res, "http://backend:8080/backend/fault/postmethod");
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

  private static void httpGet(Response res, String uri) throws IOException, ParseException {
    try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
      HttpGet httpGetOne = new HttpGet(uri);
      CloseableHttpResponse response = httpClient.execute(httpGetOne);
      res.status(response.getCode());
      HttpEntity entity = response.getEntity();
      if (entity != null) {
        String result = EntityUtils.toString(entity);
        res.body(result);
      }
    }
  }

  private static void sendPost(Response res, String url) throws IOException, ParseException {
    List<NameValuePair> urlParameters = new ArrayList<>();
    urlParameters.add(new BasicNameValuePair("body", "post"));

    try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
      HttpPost post = new HttpPost(url);
      post.setEntity(new UrlEncodedFormEntity(urlParameters));
      CloseableHttpResponse response = httpClient.execute(post);
      res.status(response.getCode());
      HttpEntity entity = response.getEntity();
      if (entity != null) {
        String result = EntityUtils.toString(entity);
        res.body(result);
      }
    }
  }
}
