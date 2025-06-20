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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/*
 * This class is copied from OTel's aws-sdk-2.2
 */
class MethodHandleFactory {

  private static String unCapitalize(String string) {
    return string.substring(0, 1).toLowerCase(Locale.ROOT) + string.substring(1);
  }

  private final ClassValue<ConcurrentHashMap<String, MethodHandle>> getterCache =
      new ClassValue<ConcurrentHashMap<String, MethodHandle>>() {
        @Override
        protected ConcurrentHashMap<String, MethodHandle> computeValue(Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  MethodHandle forField(Class<?> clazz, String fieldName)
      throws NoSuchMethodException, IllegalAccessException {
    MethodHandle methodHandle = getterCache.get(clazz).get(fieldName);
    if (methodHandle == null) {
      // getter in AWS SDK is lowercased field name
      methodHandle =
          MethodHandles.publicLookup().unreflect(clazz.getMethod(unCapitalize(fieldName)));
      getterCache.get(clazz).put(fieldName, methodHandle);
    }
    return methodHandle;
  }
}
