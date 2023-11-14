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

package com.amazon.aoc.fileconfigs;

import java.io.IOException;
import java.net.URL;

/**
 * LocalPathExpectedTemplate represents the template which comes from outside of testing framework
 * but at the same file system with terraform runtime. todo, we can probably support remote
 * templates which come from s3.
 */
public class LocalPathExpectedTemplate implements FileConfig {
  public LocalPathExpectedTemplate(String path) {
    this.path = path;
  }

  private String path;

  @Override
  public URL getPath() throws IOException {
    return new URL(path);
  }
}
