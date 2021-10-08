import { ModalExtension } from '@atlassian/clientside-extensions';
import 'regenerator-runtime/runtime'
/**
 * @clientside-extension
 * @extension-point bitbucket.ui.pullrequest.overview.summary
 */
export default ModalExtension.factory((pluginApi, context) => {
    return {
        hidden: false,
        iconBefore: 'editor/help',
        label: 'Merge Conflict Detector',
        onAction: modalApi => {
            modalApi.onMount(container => {
                var clean = true;
                modalApi.setWidth('x-large');
                modalApi.setTitle('Merge Conflict Detector');
                modalApi.setActions([
                    {
                        text: 'Close',
                        onClick: () => {
                            var attributes = clean ? {iconBefore: 'app-access'} : {iconBefore: 'warning'};
                            pluginApi.updateAttributes({iconBefore: null});
                            setTimeout(() => { pluginApi.updateAttributes(attributes); }, 100);
                            modalApi.closeModal();
                        },
                    },
                ]);

                var projectHref = window.location.href;
                var baseUrl = projectHref.split("\/projects\/")[0];
                var mcdEndpoint = baseUrl + '/rest/mcd/1.0/merge-conflicts/' + context.repository.id + '/' + context.pullRequest.id;
                container.innerHTML = '<div><p>Loading results...</p><aui-spinner size="large"></aui-spinner></div>';

                setHtmlTable();

                async function setHtmlTable() {
                  var response = await fetch(mcdEndpoint);
                  var mcdJson = await response.json(); 
                  var compareUrlPrefix = projectHref + '/repos/' + context.repository.slug + '/compare/diff?sourceBranch=' + context.pullRequest.fromRef.id + '&targetBranch=';
                  var tableHead = '<div><table class="aui"><thead><tr><th id="conflict">Result</th><th id="toBranch">Merge Target</th><th id="files">Conflicting Files</th><th id="message">(Source&nbsp;/&nbsp;Target)&nbsp;Changes</th></tr></thead>';
                  var tableData = '';
                  for(var i = 0; i < mcdJson.length; i++) {
                    var obj = mcdJson[i];
                    if (obj.mergeConflicts > 0) {
                      clean = false;
                      tableData += '<tr><td><span class="aui-lozenge aui-lozenge-error">' + obj.mergeConflicts + ' Conflicts</span></td><td>' + obj.toBranchDisplayId +'</td><td><table><tbody>';
                      for (var j = 0; j < obj.mergeFiles.length; j++) {i
                        var fileName = obj.mergeFiles[j];
                        var formattedFileName = formatFileForTable(fileName);
                        tableData += '<tr><td valign="top"><span class="aui-lozenge aui-lozenge-subtle aui-lozenge-complete"><a href="' + compareUrlPrefix + obj.toBranchId + '#' + fileName + '">Compare</a></span></td><td>' + formattedFileName + '</td></tr>';
                      }
                      tableData += '</tbody></table></td><td><table><tbody>';
                      for (var j = 0; j < obj.mergeMessages.length; j++) {
                        tableData += '<tr><td>' + formatMessageForTable(obj.mergeMessages[j]) + '</td></tr>';
                      }
                      tableData += '</tbody></table></td></tr>';
                    } else {
                      tableData += '<tr><td><span class="aui-lozenge aui-lozenge-success">Clean</span></td><td>' + obj.toBranchDisplayId +'</td><td>None</td><td>None</td></tr>';
                    }
                  }
                  var tableEnd = '</table></div>';

                  var final = tableHead + tableData + tableEnd;
                  container.innerHTML = final;
                }
               
                function formatMessageForTable(messageToBeFormatted) {
                  return messageToBeFormatted.replace(/Source change:/g,'').replace(/Target change:/g,'/');
                }

                function formatFileForTable(fileToBeFormatted) {
                  var stringReturn ='';
                  var words = fileToBeFormatted.split(' ');
                  for (var k = 0; k < words.length; k++) {
                    if (words[k].includes('/')) {
                      var elements = fileToBeFormatted.split('/');
                      if (elements.length > 6) {
                        stringReturn += elements[0] + '/' + elements[1] + '/' + elements [2] + '/.../' + elements[elements.length -3] + '/' + elements[elements.length -2] + '/' + elements[elements.length -1] + ' ';
                      }
                    } else {
                      stringReturn += words[k] + ' ';
                    }
                  }
                  return stringReturn;;
                }
            });
        }
    };
});
