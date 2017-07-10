/*
 * Copyright 2017 Vestmark, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except 
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express 
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vestmark.bitbucket.plugin;

import org.apache.commons.lang3.math.NumberUtils;

import java.util.Comparator;
import java.util.function.Function;

public class VersionComparator<T>
    implements Comparator<T>
{

  public static final VersionComparator<String> AS_STRING = new VersionComparator(Function.identity());
  public static final String DEFAULT_SPLIT_PATTERN = "\\.|-";
  private final Function<T, String> valueMapper;
  private final String splitPattern;

  public VersionComparator(Function<T, String> valueMapper)
  {
    this(valueMapper, DEFAULT_SPLIT_PATTERN);
  }

  public VersionComparator(Function<T, String> valueMapper, String splitPattern)
  {
    this.valueMapper = valueMapper;
    this.splitPattern = splitPattern;
  }

  public int compare(T o1, T o2)
  {
    String[] l = valueMapper.apply(o1).split(splitPattern);
    String[] r = valueMapper.apply(o2).split(splitPattern);
    int length = l.length < r.length ? l.length : r.length;
    for (int i = 0; i < length; i++) {
      int result = 0;
      if (NumberUtils.isNumber(l[i]) && NumberUtils.isNumber(r[i])) {
        Integer leftInt = Integer.parseInt(l[i]);
        Integer rightInt = Integer.parseInt(r[i]);
        result = leftInt.compareTo(rightInt);
      }
      else {
        result = l[i].compareTo(r[i]);
      }
      if (result != 0) {
        return result;
      }
    }
    if (l.length < r.length) {
      return -1;
    }
    if (l.length > r.length) {
      return 1;
    }
    return 0;
  }
}
