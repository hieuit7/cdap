/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import React, { useEffect, useState } from 'react';

import Checkbox from '@material-ui/core/Checkbox';
import { IWidgetProps } from 'components/AbstractWidget';
import ListItemText from '@material-ui/core/ListItemText';
import MenuItem from '@material-ui/core/MenuItem';
import Select from '@material-ui/core/Select';
import { WIDGET_PROPTYPES } from 'components/AbstractWidget/constants';
import { objectQuery } from 'services/helpers';
import withStyles, { WithStyles } from '@material-ui/core/styles/withStyles';
import ThemeWrapper from 'components/ThemeWrapper';

export interface IOption {
  id: string;
  label: string;
}

interface IMultiSelectWidgetProps {
  delimiter?: string;
  options: IOption[] | string[];
  showSelectionCount?: boolean;
  emptyPlaceholder?: string;
}
const styles = (theme) => {
  return {
    root: {
      margin: theme.Spacing(2),
    },
  };
};

interface IMultiSelectProps
  extends IWidgetProps<IMultiSelectWidgetProps>,
    WithStyles<typeof styles> {}

function MultiSelectBase({
  value,
  widgetProps,
  disabled,
  onChange,
  classes,
  dataCy,
}: IMultiSelectProps) {
  const delimiter = objectQuery(widgetProps, 'delimiter') || ',';

  let options = objectQuery(widgetProps, 'options') || [];
  // Convert 'option' to IOption if it is string
  options = options.map((opt) => {
    return typeof opt === 'string' ? { id: opt, label: opt } : opt;
  });

  const showSelectionCount = objectQuery(widgetProps, 'showSelectionCount') || false;
  const emptyPlaceholder = objectQuery(widgetProps, 'emptyPlaceholder') || '';

  const initSelection = value.toString().split(delimiter);
  const [selections, setSelections] = useState<string[]>(initSelection);

  //  onChangeHandler takes array, turns it into string w/delimiter, and calls onChange on the string
  const onChangeHandler = (event: React.ChangeEvent<HTMLSelectElement>) => {
    const values = event.target.value as any; // it's expecting a string but multiple select returns an array
    const selectionsString = values.filter((val) => val).join(delimiter);
    setSelections(values);
    onChange(selectionsString);
  };

  useEffect(() => {
    const selection = value.toString().split(delimiter);
    setSelections(selection);
  }, [value]);

  function renderValue(values: any) {
    if (selections.length === 0 || (selections.length === 1 && selections[0] === '')) {
      return emptyPlaceholder;
    }

    if (!showSelectionCount) {
      const selectionText = selections
        .map((sel) => {
          const element = options.find((op) => op.id === sel);
          return element ? element.label : '';
        })
        .join(', ');
      /**
       * TODO: Magic number alert!
       * We right now have a flexible width for widgets and we can't really
       * show ellipsis via css (yet).
       */

      const MAX_DISPLAY_TEXT_LENGTH = 75;
      return selectionText.length > MAX_DISPLAY_TEXT_LENGTH
        ? `${selectionText.slice(0, MAX_DISPLAY_TEXT_LENGTH)}...`
        : selectionText;
    }
    const selectionID = selections.find((el) => el !== '');
    const firstSelection = options.find((op) => op.id === selectionID);
    const selectionLabel = firstSelection ? firstSelection.label : '';

    let additionalSelectionCount = '';
    if (selections.length > 1) {
      additionalSelectionCount = `+${selections.length - 1}`;
    }
    return `${selectionLabel} ${additionalSelectionCount}`;
  }
  const selectionsSet = new Set(selections);
  return (
    <Select
      multiple
      value={selections}
      onChange={onChangeHandler}
      disabled={disabled}
      renderValue={renderValue}
      inputProps={{
        'data-cy': dataCy,
      }}
      data-cy={`multiselect-${dataCy}`}
      classes={classes}
    >
      {options.map((opt) => (
        <MenuItem value={opt.id} key={opt.id} data-cy={`multioption-${opt.label}`}>
          <Checkbox checked={selectionsSet.has(opt.id)} color="primary" />
          <ListItemText primary={opt.label} />
        </MenuItem>
      ))}
    </Select>
  );
}

const StyledMultiSelect = withStyles(styles)(MultiSelectBase);
export default function MultiSelect(props) {
  return (
    <ThemeWrapper>
      <StyledMultiSelect {...props} />
    </ThemeWrapper>
  );
}

(MultiSelect as any).propTypes = WIDGET_PROPTYPES;
(MultiSelect as any).getWidgetAttributes = () => {
  return {
    options: { type: 'IOption[]|string[]', required: true },
    showSelectionCount: { type: 'boolean', required: false },
    delimiter: { type: 'string', required: false },
    // including additional property that was found from the docs
    default: { type: 'string', required: false },
  };
};
