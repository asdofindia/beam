#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import absolute_import

import pandas as pd

from apache_beam.dataframe import expressions
from apache_beam.dataframe import frame_base


@frame_base.DeferredFrame._register_for(pd.Series)
class DeferredSeries(frame_base.DeferredFrame):
  def __array__(self, dtype=None):
    raise frame_base.WontImplementError(
        'Conversion to a non-deferred a numpy array.')


for base in ['add', 'sub', 'mul', 'div', 'truediv', 'floordiv', 'mod', 'pow']:
  for p in ['%s', 'r%s', '__%s__', '__r%s__']:
    # TODO: non-trivial level?
    name = p % base
    setattr(
        DeferredSeries,
        name,
        frame_base._elementwise_method(name, restrictions={'level': None}))
  setattr(
      DeferredSeries,
      '__i%s__' % base,
      frame_base._elementwise_method('__i%s__' % base, inplace=True))
for name in ['__lt__', '__le__', '__gt__', '__ge__', '__eq__', '__ne__']:
  setattr(DeferredSeries, name, frame_base._elementwise_method(name))
for name in ['apply', 'map', 'transform']:
  setattr(DeferredSeries, name, frame_base._elementwise_method(name))


@frame_base.DeferredFrame._register_for(pd.DataFrame)
class DeferredDataFrame(frame_base.DeferredFrame):
  def groupby(self, cols):
    # TODO: what happens to the existing index?
    # We set the columns to index as we have a notion of being partitioned by
    # index, but not partitioned by an arbitrary subset of columns.
    return DeferredGroupBy(
        expressions.ComputedExpression(
            'groupbyindex',
            lambda df: df.groupby(level=list(range(df.index.nlevels))),
            [self.set_index(cols)._expr],
            requires_partition_by_index=True,
            preserves_partition_by_index=True))

  def __getattr__(self, name):
    # Column attribute access.
    if name in self._expr.proxy().columns:
      return self[name]
    else:
      return object.__getattribute__(self, name)

  def __getitem__(self, key):
    if key in self._expr.proxy().columns:
      return self._elementwise(lambda df: df[key], 'get_column')
    else:
      raise NotImplementedError(key)

  def __setitem__(self, key, value):
    if isinstance(key, str):
      # yapf: disable
      return self._elementwise(
          lambda df, key, value: df.__setitem__(key, value),
          'set_column',
          (key, value),
          inplace=True)
    else:
      raise NotImplementedError(key)

  def set_index(self, keys, **kwargs):
    if isinstance(keys, str):
      keys = [keys]
    if not set(keys).issubset(self._expr.proxy().columns):
      raise NotImplementedError(keys)
    return self._elementwise(
        lambda df: df.set_index(keys, **kwargs),
        'set_index',
        inplace=kwargs.get('inplace', False))

  def at(self, *args, **kwargs):
    raise NotImplementedError()

  @property
  def loc(self):
    return _DeferredLoc(self)


for meth in ('filter', ):
  setattr(DeferredDataFrame, meth, frame_base._elementwise_method(meth))


class DeferredGroupBy(frame_base.DeferredFrame):
  def agg(self, fn):
    if not callable(fn):
      raise NotImplementedError(fn)
    return DeferredDataFrame(
        expressions.ComputedExpression(
            'agg',
            lambda df: df.agg(fn), [self._expr],
            requires_partition_by_index=True,
            preserves_partition_by_index=True))


def _liftable_agg(meth):
  name, func = frame_base.name_and_func(meth)

  def wrapper(self, *args, **kargs):
    assert isinstance(self, DeferredGroupBy)
    ungrouped = self._expr.args()[0]
    pre_agg = expressions.ComputedExpression(
        'pre_combine_' + name,
        lambda df: func(df.groupby(level=list(range(df.index.nlevels)))),
        [ungrouped],
        requires_partition_by_index=False,
        preserves_partition_by_index=True)
    post_agg = expressions.ComputedExpression(
        'post_combine_' + name,
        lambda df: func(df.groupby(level=list(range(df.index.nlevels)))),
        [pre_agg],
        requires_partition_by_index=True,
        preserves_partition_by_index=True)
    return frame_base.DeferredFrame.wrap(post_agg)

  return wrapper


def _unliftable_agg(meth):
  name, func = frame_base.name_and_func(meth)

  def wrapper(self, *args, **kargs):
    assert isinstance(self, DeferredGroupBy)
    ungrouped = self._expr.args()[0]
    post_agg = expressions.ComputedExpression(
        name,
        lambda df: func(df.groupby(level=list(range(df.index.nlevels)))),
        [ungrouped],
        requires_partition_by_index=True,
        preserves_partition_by_index=True)
    return frame_base.DeferredFrame.wrap(post_agg)

  return wrapper

LIFTABLE_AGGREGATIONS = ['all', 'any', 'max', 'min', 'prod', 'size', 'sum']
UNLIFTABLE_AGGREGATIONS = ['mean', 'median', 'std', 'var']

for meth in LIFTABLE_AGGREGATIONS:
  setattr(DeferredGroupBy, meth, _liftable_agg(meth))
for meth in UNLIFTABLE_AGGREGATIONS:
  setattr(DeferredGroupBy, meth, _unliftable_agg(meth))


class _DeferredLoc(object):
  def __init__(self, frame):
    self._frame = frame

  def __getitem__(self, index):
    if isinstance(index, tuple):
      rows, cols = index
      return self[rows][cols]
    elif isinstance(index, list) and index and isinstance(index[0], bool):
      # Aligned by numerical index.
      raise NotImplementedError(type(index))
    elif isinstance(index, list):
      # Select rows, but behaves poorly on missing values.
      raise NotImplementedError(type(index))
    elif isinstance(index, slice):
      args = [self._frame._expr]
      func = lambda df: df.loc[index]
    elif isinstance(index, frame_base.DeferredFrame):
      args = [self._frame._expr, index._expr]
      func = lambda df, index: df.loc[index]
    elif callable(index):

      def checked_callable_index(df):
        computed_index = index(df)
        if isinstance(computed_index, tuple):
          row_index, _ = computed_index
        else:
          row_index = computed_index
        if isinstance(row_index, list) and row_index and isinstance(
            row_index[0], bool):
          raise NotImplementedError(type(row_index))
        elif not isinstance(row_index, (slice, pd.Series)):
          raise NotImplementedError(type(row_index))
        return computed_index

      args = [self._frame._expr]
      func = lambda df: df.loc[checked_callable_index]
    else:
      raise NotImplementedError(type(index))

    return frame_base.DeferredFrame.wrap(
        expressions.ComputedExpression(
            'loc',
            func,
            args,
            requires_partition_by_index=len(args) > 1,
            preserves_partition_by_index=True))
