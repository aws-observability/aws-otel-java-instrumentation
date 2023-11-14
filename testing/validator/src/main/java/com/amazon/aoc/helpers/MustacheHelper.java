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

package com.amazon.aoc.helpers;

import com.amazon.aoc.fileconfigs.FileConfig;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.IOUtils;

@Log4j2
public class MustacheHelper {
  private MustacheFactory mustacheFactory = new DefaultMustacheFactory();

  /**
   * Render the template file with injecting the data.
   *
   * @param fileConfig any object implementing the FileConfig interface
   * @param dataToInject the object to inject to the template
   * @return generated content
   * @throws IOException when the template file is not existed
   */
  public String render(FileConfig fileConfig, Object dataToInject) throws IOException {
    return render(fileConfig.getPath(), dataToInject);
  }

  private String render(URL path, Object dataToInject) throws IOException {
    log.info("fetch config: {}", path);
    String templateContent = IOUtils.toString(path);
    Mustache mustache = mustacheFactory.compile(new StringReader(templateContent), path.getPath());
    StringWriter stringWriter = new StringWriter();
    mustache.execute(stringWriter, dataToInject).flush();
    return stringWriter.getBuffer().toString();
  }
}
