document.addEventListener('DOMContentLoaded', () => {
    // Elementos da UI
    const createListBtn = document.getElementById('createListBtn');
    const backBtn = document.getElementById('backBtn');
    const mainScreen = document.querySelector('.main-screen');
    const productsScreen = document.querySelector('.products-screen');

    // Event Listeners básicos
    createListBtn.addEventListener('click', () => {
        mainScreen.style.display = 'none';
        productsScreen.style.display = 'block';
        carregarProdutos(); // Carrega produtos quando abrir a tela
    });

    backBtn.addEventListener('click', () => {
        productsScreen.style.display = 'none';
        mainScreen.style.display = 'block';
    });

    // Função para carregar produtos
    async function carregarProdutos() {
        try {
            const response = await fetch('./data/produtos.json');
            const data = await response.json();
            console.log('Produtos carregados:', data);
            renderizarProdutos(data.categorias);
        } catch (error) {
            console.error('Erro ao carregar produtos:', error);
        }
    }

    // Função para criar elementos HTML dos produtos
    function renderizarProdutos(categorias) {
        const productsList = document.querySelector('.products-list');
        productsList.innerHTML = '';

        categorias.forEach(categoria => {
            const categoryDiv = document.createElement('div');
            categoryDiv.className = 'category';
            
            let subcategoriasHTML = categoria.subcategorias.map(sub => {
                let itensHTML = sub.itens.map(item => {
                    if (typeof item === 'string') {
                        return `<label>
                            <input type="checkbox" name="produto" value="${item}">
                            <span>${item}</span>
                        </label>`;
                    } else if (item.tipos) {
                        return item.tipos.map(tipo => `
                            <label>
                                <input type="checkbox" name="produto" value="${item.nome} - ${tipo}">
                                <span>${item.nome} - ${tipo}</span>
                            </label>
                        `).join('');
                    }
                    return '';
                }).join('');

                return `
                    <div class="subcategory">
                        <h4>${sub.nome}</h4>
                        <div class="items">${itensHTML}</div>
                    </div>`;
            }).join('');

            categoryDiv.innerHTML = `
                <h3>${categoria.id}. ${categoria.nome}</h3>
                ${subcategoriasHTML}
            `;
            
            productsList.appendChild(categoryDiv);
        });

        // Adicionar evento para destacar items selecionados
        document.querySelectorAll('.items label').forEach(label => {
            label.querySelector('input[type="checkbox"]').addEventListener('change', (e) => {
                if (e.target.checked) {
                    label.classList.add('checked');
                } else {
                    label.classList.remove('checked');
                }
                updateSelectedItems();
            });
        });

        // Atualizar eventos dos checkboxes após renderizar
        atualizarEventosCheckbox();
    }

    function atualizarEventosCheckbox() {
        // Selecionar todos os checkboxes novamente após renderização
        const checkboxes = document.querySelectorAll('.items input[type="checkbox"]');
        const selectedList = document.getElementById('selectedList');
        const selectedCount = document.getElementById('selectedCount');

        checkboxes.forEach(checkbox => {
            checkbox.addEventListener('change', () => {
                // Atualizar lista de itens selecionados
                const selecionados = Array.from(checkboxes)
                    .filter(cb => cb.checked)
                    .map(cb => cb.value);
                
                // Atualizar contador
                selectedCount.textContent = selecionados.length;
                
                // Atualizar lista
                selectedList.innerHTML = '';
                selecionados.forEach(item => {
                    const li = document.createElement('li');
                    li.textContent = item;
                    selectedList.appendChild(li);
                });

                // Adicionar classe para estilo visual
                const label = checkbox.closest('label');
                if (checkbox.checked) {
                    label.classList.add('checked');
                } else {
                    label.classList.remove('checked');
                }
            });
        });
    }

    const searchInput = document.getElementById('searchInput');
    const selectedList = document.getElementById('selectedList');
    const checkboxes = document.querySelectorAll('input[type="checkbox"]');

    // Função para atualizar lista de itens selecionados
    function updateSelectedItems() {
        selectedList.innerHTML = '';
        let count = 0;
        
        checkboxes.forEach(checkbox => {
            if (checkbox.checked) {
                count++;
                const li = document.createElement('li');
                li.textContent = checkbox.value;
                selectedList.appendChild(li);
            }
        });
        
        document.getElementById('selectedCount').textContent = count;
    }

    // Evento para checkboxes
    checkboxes.forEach(checkbox => {
        checkbox.addEventListener('change', updateSelectedItems);
    });

    // Função de busca
    searchInput.addEventListener('input', (e) => {
        const searchTerm = e.target.value.toLowerCase();
        const labels = document.querySelectorAll('.items label');
        
        labels.forEach(label => {
            const text = label.textContent.toLowerCase();
            label.style.display = text.includes(searchTerm) ? '' : 'none';
        });
    });

    // Adicionar função para gerar PDF
    const generatePdfBtn = document.getElementById('generatePdfBtn');
    
    generatePdfBtn.addEventListener('click', () => {
        const { jsPDF } = window.jspdf;
        const doc = new jsPDF();
        
        // Configurações da página
        const pageWidth = doc.internal.pageSize.getWidth();
        const pageHeight = doc.internal.pageSize.getHeight();
        const margin = 20;
        const colWidth = (pageWidth - 2 * margin) / 3;
        
        // Desenhar ícone do carrinho
        function desenharCarrinho(x, y) {
            // Desenho simplificado de um carrinho de compras
            doc.setDrawColor(0);
            doc.setLineWidth(0.5);
            
            // Base do carrinho
            doc.line(x, y, x + 10, y);
            doc.line(x, y, x + 2, y - 5);
            doc.line(x + 10, y, x + 8, y - 5);
            doc.line(x + 2, y - 5, x + 8, y - 5);
            
            // Rodas
            doc.circle(x + 2, y + 1, 0.8, 'F');
            doc.circle(x + 8, y + 1, 0.8, 'F');
        }
        
        // Título centralizado com ícone
        doc.setFont("helvetica", "bold");
        doc.setFontSize(18);
        const title = "Lista de Compra Miriam";
        const titleWidth = doc.getStringUnitWidth(title) * doc.internal.getFontSize() / doc.internal.scaleFactor;
        const titleX = (pageWidth - titleWidth) / 2;
        
        // Desenhar carrinho antes do título
        desenharCarrinho(titleX - 15, margin);
        
        // Escrever título
        doc.text(title, titleX, margin);
        
        // Data
        doc.setFontSize(12);
        doc.setFont("helvetica", "normal");
        doc.text("Data: " + new Date().toLocaleDateString(), margin, margin + 10);
        
        // Pegar itens selecionados
        const selectedItems = Array.from(document.querySelectorAll('.items input[type="checkbox"]:checked'))
            .map(checkbox => checkbox.value);
        
        // Configurações para as colunas
        let currentColumn = 0;
        let yPosition = margin + 25;
        const itemHeight = 7;
        const maxYPosition = pageHeight - margin;
        const checkboxSize = 3;
        
        // Desenhar itens em colunas
        selectedItems.forEach((item, index) => {
            // Verificar se precisa mudar de coluna ou página
            if (yPosition > maxYPosition - itemHeight) {
                if (currentColumn < 2) {
                    currentColumn++;
                    yPosition = margin + 25;
                } else {
                    doc.addPage();
                    currentColumn = 0;
                    yPosition = margin + 25;
                }
            }
            
            // Calcular posição X baseado na coluna atual
            const xPosition = margin + (currentColumn * colWidth);
            
            // Desenhar checkbox
            doc.rect(xPosition, yPosition - 3, checkboxSize, checkboxSize);
            
            // Escrever item
            doc.text(`${index + 1}. ${item}`, xPosition + 5, yPosition);
            
            yPosition += itemHeight;
        });
        
        // Adicionar rodapé
        doc.setFontSize(10);
        const totalItems = selectedItems.length;
        doc.text(`Total de itens: ${totalItems}`, margin, pageHeight - 10);
        
        // Salvar o PDF
        doc.save('lista-de-compras.pdf');
    });
});
